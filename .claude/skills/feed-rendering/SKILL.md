---
name: feed-rendering
description: How Ravelry posts/topics parse into a PostDocument and render on feed cards and full topics. Use when changing post/topic parsing, preview flattening, the PostBody renderer, emoji handling, or unread/mark-read counts — and to avoid breaking the two-walker preview-sync invariant.
---

# Feed rendering (PostDocument pipeline)

All code below is in the `:common` and `:composeApp` Gradle modules. **GOTCHA: the Gradle
root is `src/platform/android/`, NOT the repo root** — run every `./gradlew` from there. Source
lives under `src/common/logic/` and `src/common/compose/` (see the fibersocial-build skill).

Key files (repo-relative):
- `src/common/logic/commonMain/kotlin/com/autom8ed/fibersocial/feed/html/PostDocument.kt` — the model
- `.../feed/html/HtmlPostParser.kt` — the single HTML→PostDocument converter
- `.../feed/html/MarkdownPostParser.kt` — Markdown entry point + `plainText()` walker + `parseBodyDocument`/`parseSummaryDocument`
- `.../feed/html/PreviewInlines.kt` — `previewInlines()` walker
- `src/common/compose/commonMain/kotlin/com/autom8ed/fibersocial/feed/PostBody.kt` — the rich renderer
- `.../feed/TopicCard.kt` — feed card; `.../feed/TopicDetailScreen.kt` — full topic
- `src/common/.../feed/FeedRepository.kt` — unread count

## Fast verification

Whenever you touch parsing or a preview walker, run the invariant tests (from `src/platform/android/`):

```bash
cd src/platform/android
./gradlew :common:jvmTest --tests '*PreviewInlinesTest' --tests '*MarkdownPostParserTest' --tests '*HtmlPostParserTest'
```

Tests live in `src/common/logic/commonTest/kotlin/com/autom8ed/fibersocial/feed/html/`. For the
full gate before a PR run `bash common/test.sh` from `src/` (see the fibersocial-testing skill).

## 1. The model (`PostDocument.kt`)

```
data class PostDocument(val blocks: List<PostBlock>)
```

- `sealed interface PostBlock`: `Paragraph(content)`, `Heading(level, content)`,
  `BulletList(items: List<List<PostBlock>>)`, `OrderedList(items: List<List<PostBlock>>)`,
  `Quote(blocks)`, `CodeBlock(code)`, `Table(headerRow, rows)`, `data object Divider`.
- `sealed interface Inline`: `Text(text)`, `Styled(style: InlineStyle, children)`,
  `Code(text)`, `Link(href, children)`, `Image(url, alt, cssClass, width?, height?)`,
  `data object HardBreak`.
- Enums: `InlineStyle` (BOLD, ITALIC, STRIKETHROUGH, SMALL, BIG, SUBSCRIPT, SUPERSCRIPT),
  `CellAlignment` (LEFT, CENTER, RIGHT). `TableCell(content, alignment)`.
- `Image.isInlineEmoji` (computed): true if `cssClass` contains an `emo` token
  **or** width and height are both `<= 30`px (`INLINE_EMOJI_MAX_DIMENSION_PX`). This is the
  gate every walker/renderer uses to tell a smiley from a content photo.

The interfaces are `sealed` on purpose: adding a variant forces every `when` over them to
add a branch or fail to compile. **That guarantees exhaustiveness, NOT correct policy** — see §4.

## 2. Two parse entry points, ONE converter

- `HtmlPostParser.parse(bodyHtml)` — the shared HTML→PostDocument converter (Ksoup). Lenient:
  unknown block tags unwrap in place; unknown inline tags degrade to their children and emit
  `println("FiberSocial: HtmlPostParser unwrapping unknown inline tag <$tag>")` (a Ravelry-renderer
  drift signal — visible in logcat, see the fibersocial logging convention).
- `MarkdownPostParser.parse(markdown, renderedHtml = "")` — renders GFM Markdown source to HTML
  locally (`org.intellij.markdown`), then **reuses `HtmlPostParser`**. Separately harvests
  `:shortcode:` emoji out of the API's `body_html` (`renderedHtml`) via `harvestEmoji`/`substituteEmoji`
  and splices `Inline.Image` emoji back into the text.

**TRAP: never add a second HTML→PostDocument path.** Both entry points funnel through
`HtmlPostParser` so block/inline handling stays in one place. New tag support goes in `HtmlPostParser`.

## 3. Which source wins (do not "simplify" these — they are deliberately opposite)

- `Post.parseBodyDocument()` — **prefers the Markdown `body`**; falls back to `bodyHtml` only when
  `body` is blank. (Server `body_html` silently drops image paragraphs — issue #102 — so the
  Markdown source is canonical for full post bodies.)
- `FeedItem.parseSummaryDocument()` — **INVERTS it: prefers `bodySummaryHtml`**, falls back to the
  Markdown `bodySummary` only when the HTML is blank. (Topic summaries are truncated mid-syntax in
  source — a `**` that never closes, issue #104 — and the server HTML shows the auto-closed form.)

Both `parseBodyDocument` and `parseSummaryDocument` are extension functions at the bottom of
`MarkdownPostParser.kt`.

## 4. THE CENTERPIECE — the two-walker sync invariant

Two functions flatten the SAME `PostDocument` for compact previews and MUST agree on the visible
text every node collapses to:

- `PostDocument.previewInlines(maxLength = 200): List<Inline>` in `PreviewInlines.kt` — keeps
  styling, returns a styled `List<Inline>`; drops full photos; inline-emoji → its `alt` as text;
  budget-clips at `maxLength`.
- `MarkdownPostParser.plainText(markdown): String` in `MarkdownPostParser.kt` — parses then walks
  via `blockPlainText`/`inlinePlainText`; unwraps styling and links to their text; images → `""`;
  final regex pass strips a dangling unclosed `**` (`LITERAL_EMPHASIS_RUN`).

Both must AGREE on the resulting VISIBLE TEXT for each node — they need not produce the same
*shape*: `previewInlines()` returns styled `List<Inline>` and deliberately KEEPS the
`Styled`/`Link` wrapper (`inline.copy(children = …)`) so the renderer can still emphasize/tap
it, while `plainText()` flattens everything to a bare `String`. What must never diverge is the
text a reader sees:

| node | both walkers' visible text |
| --- | --- |
| block separator | single space |
| `HardBreak` | → space |
| `Link` | → its inner text (previewInlines keeps the `Link` wrapper around that text; plainText drops the href, leaving the bare text) |
| `Styled` | → its inner text (previewInlines keeps the `Styled` wrapper; plainText flattens it to the bare children text) |
| inline emoji (`isInlineEmoji`) | → its `alt` text |
| full photo | → dropped / `""` |

**TRAP: `sealed` protects exhaustiveness, not policy.** Adding a new `PostBlock`/`Inline` variant
breaks BOTH `when`s at compile time so you cannot forget one. But **changing how an existing node
flattens in one walker will compile fine while silently diverging the other** — there is no shared
helper. When you touch one walker's collapse policy, mirror it BY HAND in the other, then run:

```bash
cd src/platform/android
./gradlew :common:jvmTest --tests '*PreviewInlinesTest' --tests '*MarkdownPostParserTest'
```

`PreviewInlinesTest.kt` + `MarkdownPostParserTest.kt` are what guard the invariant. If you add a
variant or change a policy, add a case to BOTH.

(Note: today both walkers are referenced only from tests — they are maintained library functions
for compact-preview surfaces. The live feed card renders the summary in full via `PostBody`, §5.)

## 5. The rich renderer (`PostBody.kt`)

`@Composable fun PostBody(document: PostDocument, modifier, interactive: Boolean = true)` is the
single renderer for both the feed card and the full post (AnnotatedString + InlineTextContent for
images/emoji, a custom Layout for tables, tap handling for links).

- Feed card (`TopicCard.kt`): renders the summary IN FULL via `item.parseSummaryDocument()` +
  `PostBody(document, interactive = false)`. **GOTCHA: `interactive = false` is load-bearing (#216)** —
  with it, link/image taps are disabled so a tap anywhere on the summary opens the topic (the whole
  card is the tap target) instead of a link swallowing the tap.
- Full topic (`TopicDetailScreen.kt`): the opener summary renders via `topic.parseSummaryDocument()`
  and each reply via `post.parseBodyDocument()`, both inside `PostBody(...)` with the default
  `interactive = true`.

**Lesson from #66/#105 — attribution, not blank HTML, is the reply-vs-opener signal.** When deciding
whether a card preview shows a reply versus the opener, gate on the reply's *author/attribution*
signal, not on whether the reply HTML is blank — a blank-but-attributed reply must NOT fall back to
printing the opener's words under the replier's name. (`FeedItem` currently carries only the opener
summary; keep this principle in mind before adding any reply-preview field.)

## 6. Unread / mark-read

- `unreadCount` is computed in `FeedRepository.toFeedItem` as
  `(postsCount - lastRead).coerceAtLeast(0)`. **GOTCHA: it uses `postsCount` (the latest post
  number), NOT Ravelry's `latest_reply` field — `latest_reply` comes back `0` in practice.**
- `RavelryApiClient.markTopicRead(topicId, lastRead)` → `POST $BASE_URL/topics/{id}/read.json`.
  Ravelry only ever advances the marker, so it is safe to over-call.
- `TopicDetailScreen` tracks a high-water mark `furthestSeen` (`mutableStateOf(0)`, keyed on
  `topic.id`). **Every exit path must sync it first:** `handleBack = { onMarkRead(furthestSeen); ... }`
  is wired to BOTH the top-bar back arrow (`IconButton(onClick = handleBack)`) and the system
  `BackHandler(onBack = handleBack)`. "Mark all as read" (#227) bypasses scrolling by calling
  `onMarkRead(topic.postCount)` directly. **TRAP: if you add a new way to leave the screen, route it
  through `handleBack`** or the read marker won't advance and the unread badge will lie.
