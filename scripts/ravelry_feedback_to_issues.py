#!/usr/bin/env python3
"""Convert FiberSocial in-app feedback (Ravelry support-group forum topics) into GitHub issues,
and (with a write-capable login) post a reply back on each topic linking to the created issue.

The app posts feedback as forum topics in the "FiberSocial App Support" group
(forum id 50803), titling each with an "[App Feedback] " prefix
(see src/common/.../feedback/FeedbackViewModel.kt). This script reads those topics
and opens one GitHub issue per new topic, capturing the whole thread (the opening
post plus every follow-up reply).

IDEMPOTENT: every issue embeds a hidden marker `<!-- ravelry-topic-id: N -->`; each run
first scans existing issues (open + closed) for those markers and skips topics already
imported, so it never double-imports and never double-replies. Safe to run on a cron.

--------------------------------------------------------------------------------
AUTH — three modes, in priority order
--------------------------------------------------------------------------------
1. OAuth bearer token (READ + WRITE — required for --link-back).
   The app's client_id/client_secret are OAuth2 *client* credentials: on their own they
   can't call data endpoints. Run a ONE-TIME login to mint a user token from them:
       ./ravelry_feedback_to_issues.py --login \
           --local-properties /path/to/local.properties     # supplies ravelry.client_id/secret
   That opens a browser, you authorize, and a refreshable token is cached at
   ~/.config/fibersocial/ravelry_token.json (chmod 600). Subsequent runs reuse/refresh it.
   PREREQUISITE: add the redirect URI (default https://localhost:8731/callback — Ravelry
   requires https) to your Ravelry OAuth app at https://www.ravelry.com/pro/developer.
   Nothing needs to actually listen there: after you authorize, the browser shows a
   harmless "can't reach localhost" page and you paste the URL (which holds ?code=) back.
   Override the URI with --redirect-uri.
   You can also just export RAVELRY_OAUTH_TOKEN=... if you already have a bearer token.

2. Read-only Basic Auth keys (READ ONLY — cannot --link-back).
   Create a read-only key pair at https://www.ravelry.com/pro/developer and either
       export RAVELRY_ACCESS_KEY=...  RAVELRY_PERSONAL_KEY=...
   or put ravelry.access_key / ravelry.personal_key in --local-properties.

3. Interactive prompt (read-only key pair, hidden input) if nothing above is set.

GitHub: uses the `gh` CLI — run `gh auth login` first (or set GH_TOKEN).

--------------------------------------------------------------------------------
USAGE
--------------------------------------------------------------------------------
  # one-time authorization (write access, enables link-back):
  ./ravelry_feedback_to_issues.py --login --local-properties .../local.properties

  ./ravelry_feedback_to_issues.py --dry-run                 # preview, create nothing
  ./ravelry_feedback_to_issues.py                           # create issues
  ./ravelry_feedback_to_issues.py --link-back               # create issues AND reply on Ravelry
  ./ravelry_feedback_to_issues.py --all-topics --limit 5

Exit codes: 0 = ok, 1 = a create/reply failed, 2 = config/auth/setup error.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import http.server
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

# --- FiberSocial constants (mirror of SupportGroup.kt / FeedbackViewModel.kt / auth/*) ---
RAVELRY_API = "https://api.ravelry.com"
RAVELRY_WWW = "https://www.ravelry.com"
OAUTH_AUTH_URL = "https://www.ravelry.com/oauth2/auth"
OAUTH_TOKEN_URL = "https://www.ravelry.com/oauth2/token"
OAUTH_SCOPE = "forum-write offline"  # forum-write => can post the link-back reply; offline => refresh token
SUPPORT_FORUM_ID = 50803
SUPPORT_GROUP_PERMALINK = "fibersocial-app-support"
FEEDBACK_TITLE_PREFIX = "[App Feedback] "

DEFAULT_REPO = "torrey1028/FiberSocial"
DEFAULT_LABEL = "feedback"
DEFAULT_REDIRECT = "https://localhost:8731/callback"  # Ravelry requires https (rejects plain http)
TOKEN_CACHE = os.path.expanduser("~/.config/fibersocial/ravelry_token.json")
MARKER_RE = re.compile(r"<!--\s*ravelry-topic-id:\s*(\d+)\s*-->")
PAGE_SIZE = 100
REQUEST_PAUSE_S = 0.35
UA = "fibersocial-feedback-to-issues/2.0"

_AUTH_HEADER: str | None = None   # resolved once in main()
_CAN_WRITE = False                # True only when authed with an OAuth bearer token


def die(msg: str, code: int = 2) -> None:
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(code)


# ------------------------------- Ravelry HTTP ---------------------------------

def _ravelry(method: str, path: str, params: dict | None = None, form: dict | None = None) -> dict:
    url = f"{RAVELRY_API}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    headers = {"Authorization": _AUTH_HEADER, "Accept": "application/json", "User-Agent": UA}
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")[:300]
        if e.code in (401, 403):
            die(f"Ravelry auth failed ({e.code}). Check your credentials, that the account is a "
                f"member of '{SUPPORT_GROUP_PERMALINK}', and (for writes) that you logged in with "
                f"--login. Body: {body}")
        die(f"Ravelry {method} {path} -> HTTP {e.code}: {body}")
    except urllib.error.URLError as e:
        die(f"Ravelry {method} {path} failed: {e.reason}")


def ravelry_get(path: str, params: dict | None = None) -> dict:
    return _ravelry("GET", path, params=params)


def ravelry_post(path: str, form: dict) -> dict:
    return _ravelry("POST", path, form=form)


def fetch_all_topics() -> list[dict]:
    topics: list[dict] = []
    page = 1
    while True:
        data = ravelry_get(
            f"/forums/{SUPPORT_FORUM_ID}/topics.json",
            {"page": page, "page_size": PAGE_SIZE, "sort": "created_"},  # created_ = newest first
        )
        batch = data.get("topics", [])
        topics.extend(batch)
        paginator = data.get("paginator") or {}
        if page >= int(paginator.get("page_count", page)) or not batch:
            break
        page += 1
        time.sleep(REQUEST_PAUSE_S)
    return topics


def fetch_all_posts(topic_id: int) -> list[dict]:
    """All posts in a topic (opening post first, then replies oldest-first), across pages."""
    posts: list[dict] = []
    page = 1
    while True:
        data = ravelry_get(f"/topics/{topic_id}/posts.json", {"page": page, "page_size": PAGE_SIZE})
        batch = data.get("posts", [])
        posts.extend(batch)
        paginator = data.get("paginator") or {}
        if page >= int(paginator.get("page_count", page)) or not batch:
            break
        page += 1
        time.sleep(REQUEST_PAUSE_S)
    return posts


# ----------------------------- OAuth2 (PKCE) login ----------------------------

def _pkce() -> tuple[str, str]:
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def _token_request(client_id: str, client_secret: str, form: dict) -> dict:
    data = urllib.parse.urlencode(form).encode()
    basic = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
    req = urllib.request.Request(OAUTH_TOKEN_URL, data=data, method="POST", headers={
        "Authorization": f"Basic {basic}",
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept": "application/json", "User-Agent": UA,
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            tok = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        die(f"OAuth token endpoint HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")
    except urllib.error.URLError as e:
        die(f"OAuth token endpoint unreachable: {e.reason}")
    if "access_token" not in tok:
        die(f"OAuth token response missing access_token: {json.dumps(tok)[:200]}")
    tok["obtained_at"] = int(time.time())
    return tok


def _save_token(tok: dict) -> None:
    os.makedirs(os.path.dirname(TOKEN_CACHE), exist_ok=True)
    with open(TOKEN_CACHE, "w", encoding="utf-8") as f:
        json.dump(tok, f)
    os.chmod(TOKEN_CACHE, 0o600)


def _load_token() -> dict | None:
    try:
        with open(TOKEN_CACHE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def oauth_login(client_id: str, client_secret: str, redirect_uri: str) -> str:
    """Interactive one-time authorization-code + PKCE login. Returns the access token."""
    verifier, challenge = _pkce()
    state = secrets.token_urlsafe(16)
    auth_url = OAUTH_AUTH_URL + "?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": client_id, "redirect_uri": redirect_uri,
        "scope": OAUTH_SCOPE, "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256",
    })

    holder: dict = {}
    port = urllib.parse.urlparse(redirect_uri).port or 80

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_a):  # silence access logs
            pass

        def do_GET(self):  # noqa: N802
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            holder["code"] = (q.get("code") or [None])[0]
            holder["state"] = (q.get("state") or [None])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(b"<h2>FiberSocial: authorized. You can close this tab and return "
                             b"to the terminal.</h2>")

    print("\nOpen this URL in a browser and authorize (Ravelry login/allow page):\n")
    print("  " + auth_url + "\n")
    try:
        webbrowser.open(auth_url)
    except Exception:  # noqa: BLE001 - headless/WSL may have no browser; the printed URL is the fallback
        pass

    # Auto-capture works only over plain http (a local https server would need a TLS cert).
    # Ravelry requires https redirect URIs, so https falls through to the manual-paste flow.
    scheme = urllib.parse.urlparse(redirect_uri).scheme
    if scheme == "http":
        try:
            server = http.server.HTTPServer(("127.0.0.1", port), Handler)
            server.timeout = 180
            print(f"Waiting up to 3 min for the redirect on {redirect_uri} ...")
            server.handle_request()
            server.server_close()
        except OSError as e:
            print(f"(could not listen on {redirect_uri}: {e})")

    if not holder.get("code"):
        if scheme != "http":
            print(f"\nAfter you click Authorize, the browser will try to open\n  {redirect_uri}?code=..."
                  "\nand show a harmless 'can't reach this page' / cert warning (nothing is listening")
            print("there). Copy the FULL URL from the address bar — it contains ?code= — and paste it.")
        if not sys.stdin.isatty():
            die("no authorization code captured, and not running interactively. Run --login in a terminal.")
        pasted = input("\n  Paste the full redirected URL: ").strip()
        q = urllib.parse.parse_qs(urllib.parse.urlparse(pasted).query)
        holder["code"] = (q.get("code") or [None])[0]
        holder["state"] = (q.get("state") or [None])[0]

    if not holder.get("code"):
        die("no authorization code received. Make sure the redirect URI above is registered on "
            "your Ravelry OAuth app (Pro -> Developer).")
    if holder.get("state") != state:
        die("OAuth state mismatch — aborting for safety.")

    tok = _token_request(client_id, client_secret, {
        "grant_type": "authorization_code", "code": holder["code"],
        "redirect_uri": redirect_uri, "code_verifier": verifier,
    })
    _save_token(tok)
    print(f"Authorized. Token cached at {TOKEN_CACHE}")
    return tok["access_token"]


def valid_cached_token(client_id: str | None, client_secret: str | None) -> str | None:
    """Return a usable access token from cache, refreshing if expired; else None."""
    tok = _load_token()
    if not tok or "access_token" not in tok:
        return None
    expires_at = int(tok.get("obtained_at", 0)) + int(tok.get("expires_in", 0))
    if time.time() < expires_at - 60:
        return tok["access_token"]
    # Expired: try a refresh (needs client creds + a refresh token).
    refresh = tok.get("refresh_token")
    if refresh and client_id and client_secret:
        new = _token_request(client_id, client_secret, {
            "grant_type": "refresh_token", "refresh_token": refresh,
        })
        new.setdefault("refresh_token", refresh)  # Ravelry may not resend it
        _save_token(new)
        return new["access_token"]
    return None


# -------------------------- Credential resolution -----------------------------

def parse_java_properties(path: str) -> dict:
    props: dict[str, str] = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if not s or s[0] in "#!":
                    continue
                for sep in ("=", ":"):
                    if sep in s:
                        k, v = s.split(sep, 1)
                        props[k.strip()] = v.strip()
                        break
    except OSError as e:
        die(f"could not read --local-properties file {path}: {e}")
    return props


def resolve_auth(args) -> tuple[str, bool]:
    """Return (Authorization header, can_write). Priority: explicit bearer -> --login ->
    cached OAuth (refreshed) -> read-only Basic keys -> interactive prompt. Never prints secrets."""
    props = parse_java_properties(args.local_properties) if args.local_properties else {}

    def pick(env_key: str, prop_key: str) -> str | None:
        return os.environ.get(env_key) or props.get(prop_key)

    client_id = pick("RAVELRY_CLIENT_ID", "ravelry.client_id")
    client_secret = pick("RAVELRY_CLIENT_SECRET", "ravelry.client_secret")

    # 1) explicit bearer token
    token = pick("RAVELRY_OAUTH_TOKEN", "ravelry.oauth_token")
    if token:
        return f"Bearer {token}", True

    # 1a) one-time login requested
    if args.login:
        if not (client_id and client_secret):
            die("--login needs ravelry.client_id + ravelry.client_secret (via --local-properties "
                "or RAVELRY_CLIENT_ID/RAVELRY_CLIENT_SECRET).")
        return f"Bearer {oauth_login(client_id, client_secret, args.redirect_uri)}", True

    # 1b) previously cached OAuth token (auto-refresh)
    cached = valid_cached_token(client_id, client_secret)
    if cached:
        return f"Bearer {cached}", True

    # 2) read-only Basic Auth keys
    access = pick("RAVELRY_ACCESS_KEY", "ravelry.access_key")
    personal = pick("RAVELRY_PERSONAL_KEY", "ravelry.personal_key")
    if access and personal:
        return "Basic " + base64.b64encode(f"{access}:{personal}".encode()).decode(), False

    # Write requested but only read-only auth is available -> steer to --login.
    if args.link_back:
        die("--link-back needs write access, which read-only keys don't have. Run once with "
            "--login (uses ravelry.client_id/secret) to authorize a write-capable token.")

    # 3) interactive prompt for the read-only key pair
    if not args.no_prompt and sys.stdin.isatty():
        print("\nEnter your Ravelry API credentials — the read-only key pair from")
        print("https://www.ravelry.com/pro/developer (the API key pair, NOT your account")
        print("password). For write access / --link-back, cancel and use --login instead.")
        print("Input is hidden and stays on this machine.\n")
        try:
            user = input("  Ravelry API username (access key): ").strip()
            pw = getpass.getpass("  Ravelry API password (personal key): ").strip()
        except (EOFError, KeyboardInterrupt):
            die("credential entry cancelled.")
        if user and pw:
            return "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode(), False
        die("no credentials entered.")

    if client_id or client_secret:
        die("found ravelry.client_id/secret but no usable token. Run with --login once to mint a "
            "bearer token from them, or provide read-only ravelry.access_key/personal_key.")
    die("no Ravelry credentials found. Use --login (with client_id/secret), set "
        "RAVELRY_ACCESS_KEY + RAVELRY_PERSONAL_KEY / RAVELRY_OAUTH_TOKEN, or run in a terminal.")


# -------------------------------- GitHub (gh) ---------------------------------

def gh(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["gh", *args], text=True, capture_output=True, check=check)


def ensure_gh_ready() -> None:
    if subprocess.run(["gh", "--version"], capture_output=True).returncode != 0:
        die("`gh` CLI not found on PATH. Install it and run `gh auth login`.")
    if gh("auth", "status", check=False).returncode != 0:
        die("`gh` is not authenticated. Run `gh auth login` (or set GH_TOKEN).")


def existing_topic_ids(repo: str) -> set[int]:
    res = gh("issue", "list", "--repo", repo, "--state", "all",
             "--limit", "1000", "--json", "number,body")
    ids: set[int] = set()
    for issue in json.loads(res.stdout or "[]"):
        for m in MARKER_RE.finditer(issue.get("body") or ""):
            ids.add(int(m.group(1)))
    return ids


def ensure_label(repo: str, label: str) -> None:
    res = gh("label", "list", "--repo", repo, "--json", "name", check=False)
    names = {l["name"] for l in json.loads(res.stdout or "[]")} if res.returncode == 0 else set()
    if label not in names:
        gh("label", "create", label, "--repo", repo, "--color", "d876e3",
           "--description", "Reported via in-app Ravelry feedback", check=False)


# --------------------------------- Transform ----------------------------------

def clean_title(raw: str) -> str:
    t = raw[len(FEEDBACK_TITLE_PREFIX):] if raw.startswith(FEEDBACK_TITLE_PREFIX) else raw
    return t.strip() or "(untitled feedback)"


def topic_url(topic_id: int) -> str:
    return f"{RAVELRY_WWW}/discuss/{SUPPORT_GROUP_PERMALINK}/{topic_id}"


GITHUB_BODY_LIMIT = 60000  # GitHub's hard cap is 65536; leave headroom for the footer/marker


def _person_link(username: str) -> str:
    return f"[@{username}]({RAVELRY_WWW}/people/{urllib.parse.quote(username)})"


def build_issue_body(topic: dict, posts: list[dict]) -> str:
    """Render the whole thread: opening post as the description, then every reply."""
    tid = int(topic["id"])
    posts = posts or []
    opening = posts[0] if posts else None

    author = (opening.get("user") or {}).get("username") if opening else None
    author = author or (topic.get("created_by_user") or {}).get("username")
    created = (opening.get("created_at") if opening else None) or topic.get("created_at")
    body_md = ((opening.get("body") if opening else "") or "").strip()
    if not body_md:
        body_md = (topic.get("summary") or "_(no description provided)_").strip()

    attribution = "Reported via **Ravelry in-app feedback**"
    if author:
        attribution += f" by {_person_link(author)}"
    if created:
        attribution += f" on {created}"

    parts = [f"{attribution}.", "", body_md]

    replies = posts[1:]
    if replies:
        parts += ["", f"## Conversation — {len(replies)} repl{'y' if len(replies) == 1 else 'ies'}", ""]
        for p in replies:
            uname = (p.get("user") or {}).get("username") or "unknown"
            when = p.get("created_at") or ""
            pbody = (p.get("body") or "").strip() or "_(empty)_"
            parts += [f"**{_person_link(uname)}{f' — {when}' if when else ''}**", "", pbody, ""]

    footer = f"\n---\n🔗 Ravelry topic: {topic_url(tid)}\n\n<!-- ravelry-topic-id: {tid} -->\n"
    body = "\n".join(parts) + footer
    if len(body) > GITHUB_BODY_LIMIT:  # keep the marker/footer so dedup still works
        keep = GITHUB_BODY_LIMIT - len(footer) - 80
        body = body[:keep].rstrip() + "\n\n… _(truncated — read the full thread on Ravelry)_" + footer
    return body


def is_feedback_topic(topic: dict, all_topics: bool) -> bool:
    if topic.get("sticky"):
        return False
    return True if all_topics else str(topic.get("title", "")).startswith(FEEDBACK_TITLE_PREFIX)


def link_back(topic_id: int, issue_url: str, message: str, dry_run: bool) -> bool:
    """Post a reply on the Ravelry topic pointing to the created GitHub issue. Needs write auth."""
    body = message.replace("{url}", issue_url)
    if dry_run:
        print(f"      would reply on topic {topic_id}: {body!r}")
        return True
    ravelry_post(f"/topics/{topic_id}/reply.json", {"body": body})
    return True


# ----------------------------------- Main -------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="Turn Ravelry app-feedback topics into GitHub issues.")
    ap.add_argument("--repo", default=os.environ.get("GH_REPO", DEFAULT_REPO),
                    help=f"owner/name (default {DEFAULT_REPO})")
    ap.add_argument("--local-properties", metavar="PATH", default=os.environ.get("LOCAL_PROPERTIES"),
                    help="read Ravelry creds from a local.properties-style file "
                         "(ravelry.client_id/secret for --login, or ravelry.access_key/personal_key, "
                         "or ravelry.oauth_token). Env vars override the file.")
    ap.add_argument("--login", action="store_true",
                    help="run a one-time OAuth login (uses client_id/secret) to mint a write-capable "
                         "token, then continue")
    ap.add_argument("--redirect-uri", default=DEFAULT_REDIRECT,
                    help=f"OAuth redirect URI for --login (default {DEFAULT_REDIRECT}); must be "
                         f"registered on your Ravelry app")
    ap.add_argument("--link-back", action="store_true",
                    help="after creating each issue, post a reply on the Ravelry topic linking to it "
                         "(requires --login / a write-capable token)")
    ap.add_argument("--link-back-message",
                    default="Thanks for the feedback! We're now tracking this on GitHub: {url}",
                    help="reply text for --link-back ('{url}' is replaced with the issue URL)")
    ap.add_argument("--label", default=DEFAULT_LABEL, help=f"issue label (default {DEFAULT_LABEL}; '' to skip)")
    ap.add_argument("--all-topics", action="store_true", help="import every non-sticky topic, not just '[App Feedback]'")
    ap.add_argument("--limit", type=int, default=0, help="cap the number of issues created (0 = no cap)")
    ap.add_argument("--dry-run", action="store_true", help="print what would happen without creating/replying")
    ap.add_argument("--no-prompt", action="store_true", help="never prompt for credentials interactively")
    args = ap.parse_args()

    global _AUTH_HEADER, _CAN_WRITE
    _AUTH_HEADER, _CAN_WRITE = resolve_auth(args)
    if args.link_back and not _CAN_WRITE:
        die("--link-back needs a write-capable OAuth token; run with --login first.")

    if not args.dry_run:
        ensure_gh_ready()

    print(f"Fetching topics from forum {SUPPORT_FORUM_ID} ({SUPPORT_GROUP_PERMALINK}) ...")
    topics = fetch_all_topics()
    candidates = [t for t in topics if is_feedback_topic(t, args.all_topics)]
    print(f"  {len(topics)} topics total, {len(candidates)} match the feedback filter.")

    already = set() if args.dry_run else existing_topic_ids(args.repo)
    if already:
        print(f"  {len(already)} already imported (skipping those).")

    todo = [t for t in candidates if int(t["id"]) not in already]
    if args.limit > 0:
        todo = todo[:args.limit]
    if not todo:
        print("Nothing new to import. Done.")
        return 0
    if not args.dry_run and args.label:
        ensure_label(args.repo, args.label)

    failures = created = 0
    for t in todo:
        tid = int(t["id"])
        title = clean_title(t.get("title", ""))
        try:
            posts = fetch_all_posts(tid)
            time.sleep(REQUEST_PAUSE_S)
        except SystemExit:
            raise
        except Exception as e:  # noqa: BLE001 - keep going on a single bad topic
            print(f"  ! topic {tid}: could not fetch posts ({e}); using summary.")
            posts = []
        body = build_issue_body(t, posts)

        if args.dry_run:
            print(f"\n--- WOULD CREATE (topic {tid}) ---\nTitle: {title}\n{body}")
            if args.link_back:
                link_back(tid, "<issue-url>", args.link_back_message, dry_run=True)
            created += 1
            continue

        cmd = ["issue", "create", "--repo", args.repo, "--title", title, "--body", body]
        if args.label:
            cmd += ["--label", args.label]
        res = gh(*cmd, check=False)
        if res.returncode != 0:
            print(f"  ! topic {tid}: gh issue create failed: {(res.stderr or '').strip()}")
            failures += 1
            continue
        issue_url = (res.stdout or "").strip().splitlines()[-1] if res.stdout else ""
        print(f"  + topic {tid} -> {issue_url}")
        created += 1

        if args.link_back and issue_url:
            try:
                link_back(tid, issue_url, args.link_back_message, dry_run=False)
                print(f"      ↳ replied on Ravelry topic {tid}")
            except SystemExit:
                raise
            except Exception as e:  # noqa: BLE001
                print(f"      ! link-back reply failed for topic {tid}: {e}")
                failures += 1
        time.sleep(REQUEST_PAUSE_S)

    verb = "would create" if args.dry_run else "created"
    print(f"\nDone. {verb} {created} issue(s); {failures} failure(s).")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
