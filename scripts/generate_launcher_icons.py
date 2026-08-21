#!/usr/bin/env python3
"""Render the launcher-icon raster assets from the SVG source art in docs/design/logo/.

Two variants exist, from two source SVGs:

    release  purple mark on lavender  #EEE9FF  (FiberSocialLogo_clearbackground.svg)
    debug    light-purple mark on     #121212  (FiberSocialLogo_darkmode.svg)

so a debug build sitting next to the store build on the same home screen is
unmistakable at a glance. The debug icon is the one this script exists for; the
release icon predates it and is regenerated only on request (`--variant release`
or `--variant all`), because re-rendering it under a different librsvg version
produces a cosmetically identical but byte-different file, and churning the
shipped store icon for nothing is a bad trade.

Every layer is a raster rather than a vector because the monogram's "FS" is set
in the Excalifont typeface embedded in the SVGs — Android's VectorDrawable has
no font support, so the text has to be rendered to pixels here.

Requires rsvg-convert (librsvg): apt install librsvg2-bin / brew install librsvg.
"""

from __future__ import annotations

import argparse
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LOGO_DIR = REPO_ROOT / "docs" / "design" / "logo"
ANDROID_RES = REPO_ROOT / "src" / "platform" / "android" / "app" / "src" / "main" / "res"
ANDROID_DEBUG_RES = REPO_ROOT / "src" / "platform" / "android" / "app" / "src" / "debug" / "res"
IOS_ASSETS = REPO_ROOT / "src" / "platform" / "ios" / "FiberSocial" / "Assets.xcassets"

# An adaptive icon's foreground layer has to stay inside the mask safe zone, so
# the art covers ~62 % of the layer rather than filling it. iOS applies no mask
# but uses the same fraction, so the two platforms' icons read as one size.
SCALE = 0.62

# mdpi's 108 px is the adaptive icon's 108 dp layer at 1x.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

VARIANTS = {
    "release": {
        "source": "FiberSocialLogo_clearbackground.svg",
        "background": "#EEE9FF",
        "android_res": ANDROID_RES,
        "ios_iconset": "AppIcon.appiconset",
        # The Play Console listing icon is the same flat square at 512 px.
        "playstore_icon": LOGO_DIR / "ic_launcher-playstore.png",
    },
    "debug": {
        "source": "FiberSocialLogo_darkmode.svg",
        "background": "#121212",
        "android_res": ANDROID_DEBUG_RES,
        "ios_iconset": "AppIconDebug.appiconset",
        "playstore_icon": None,
    },
}


def reframe(source: Path, dest: Path, background: str | None) -> None:
    """Write a copy of `source` re-framed onto a square canvas 1/SCALE as wide.

    The art then lands centered at SCALE of whatever size it is rendered to. A
    full-bleed background rect in the source (the dark-mode SVG has one) is
    dropped: Android wants a transparent foreground layer, and the flat iOS icon
    wants its background painted across the *expanded* canvas, not the original
    frame. `background` paints that expanded rect; None leaves it transparent.
    """
    svg = source.read_text()

    view_box = re.search(r'viewBox="([\d.\-]+) ([\d.\-]+) ([\d.\-]+) ([\d.\-]+)"', svg)
    if not view_box:
        sys.exit(f"ERROR: {source.name} has no viewBox")
    x, y, w, h = (float(v) for v in view_box.groups())

    # Anchored to the frame's own coordinates so a *partial* rect — one that is
    # part of the art rather than its backdrop — survives.
    svg, dropped = re.subn(
        r'<rect x="%s" y="%s" width="%s" height="%s"[^>]*></rect>'
        % tuple(re.escape(v) for v in view_box.groups()),
        "",
        svg,
        count=1,
    )
    if background and not dropped and re.search(r"<rect [^>]*>", svg):
        # Not fatal — a source with no backdrop rect at all is the normal case
        # for the clear-background art — but worth saying out loud if the art
        # changes shape, since a surviving backdrop would sit at the old size.
        print(f"  note: {source.name} kept every rect (no full-bleed backdrop found)")

    side = max(w, h) / SCALE
    nx, ny = x + w / 2 - side / 2, y + h / 2 - side / 2
    svg = svg.replace(view_box.group(0), f'viewBox="{nx} {ny} {side} {side}"', 1)

    if background:
        rect = f'<rect x="{nx}" y="{ny}" width="{side}" height="{side}" fill="{background}"></rect>'
        svg = re.sub(r"(</defs>)", lambda m: m.group(1) + rect, svg, count=1)

    dest.write_text(svg)


def render(svg: Path, dest: Path, size: int) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["rsvg-convert", "-w", str(size), "-h", str(size), str(svg), "-o", str(dest)],
        check=True,
    )


def drop_alpha(path: Path, background: str) -> None:
    """Rewrite an RGBA PNG as 24-bit RGB composited over `background`.

    App Store Connect rejects an app icon that carries an alpha channel, and
    rsvg-convert always writes one. Only the flat single-layer icons go through
    this; Android's foreground layer needs its alpha.
    """
    bg = tuple(int(background.lstrip("#")[i : i + 2], 16) for i in (0, 2, 4))
    width, height, channels, pixels = _decode_png(path)
    if channels == 3:
        return
    if channels != 4:
        sys.exit(f"ERROR: {path} is neither RGB nor RGBA")

    rgb = bytearray(width * height * 3)
    for i in range(width * height):
        r, g, b, a = pixels[i * 4 : i * 4 + 4]
        for c, (value, base) in enumerate(zip((r, g, b), bg)):
            rgb[i * 3 + c] = (value * a + base * (255 - a) + 127) // 255
    _encode_png(path, width, height, bytes(rgb))


def _decode_png(path: Path) -> tuple[int, int, int, bytes]:
    """Minimal 8-bit non-interlaced PNG decode — enough for rsvg-convert output."""
    data = path.read_bytes()
    pos, idat, width, height, color_type = 8, b"", 0, 0, 0
    while pos < len(data):
        (length,), chunk_type = struct.unpack(">I", data[pos : pos + 4]), data[pos + 4 : pos + 8]
        payload = data[pos + 8 : pos + 8 + length]
        if chunk_type == b"IHDR":
            width, height, depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", payload)
            if depth != 8 or interlace:
                sys.exit(f"ERROR: {path} is not an 8-bit non-interlaced PNG")
        elif chunk_type == b"IDAT":
            idat += payload
        pos += 12 + length

    channels = {0: 1, 2: 3, 4: 2, 6: 4}[color_type]
    raw = zlib.decompress(idat)
    stride = width * channels
    out, previous, offset = bytearray(height * stride), bytearray(stride), 0
    for row in range(height):
        filter_type, offset = raw[offset], offset + 1
        line = bytearray(raw[offset : offset + stride])
        offset += stride
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up = previous[i]
            up_left = previous[i - channels] if i >= channels else 0
            if filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                estimate = left + up - up_left
                da, db, dc = (
                    abs(estimate - left),
                    abs(estimate - up),
                    abs(estimate - up_left),
                )
                predictor = left if da <= db and da <= dc else (up if db <= dc else up_left)
                line[i] = (line[i] + predictor) & 0xFF
            elif filter_type != 0:
                sys.exit(f"ERROR: {path} uses unknown PNG filter {filter_type}")
        out[row * stride : (row + 1) * stride] = line
        previous = line
    return width, height, channels, bytes(out)


def _encode_png(path: Path, width: int, height: int, rgb: bytes) -> None:
    stride = width * 3
    raw = b"".join(b"\x00" + rgb[y * stride : (y + 1) * stride] for y in range(height))

    def chunk(chunk_type: bytes, payload: bytes) -> bytes:
        body = chunk_type + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body))

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def generate(variant: str, work: Path) -> None:
    spec = VARIANTS[variant]
    source = LOGO_DIR / spec["source"]
    print(f"==> {variant} launcher icon ({source.name})")

    # Android: transparent adaptive-icon foreground layer, one per density. The
    # matching background colour is a resource, not an image — see the variant's
    # res/values/ic_launcher_background.xml.
    foreground = work / f"{variant}-foreground.svg"
    reframe(source, foreground, background=None)
    for density, size in DENSITIES.items():
        render(foreground, spec["android_res"] / f"mipmap-{density}" / "ic_launcher_foreground.png", size)

    # iOS: no layers, one flat 1024 px square with the background baked in.
    flat = work / f"{variant}-flat.svg"
    reframe(source, flat, background=spec["background"])
    ios_icon = IOS_ASSETS / spec["ios_iconset"] / "AppIcon.png"
    render(flat, ios_icon, 1024)
    drop_alpha(ios_icon, spec["background"])

    if spec["playstore_icon"]:
        render(flat, spec["playstore_icon"], 512)
        drop_alpha(spec["playstore_icon"], spec["background"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--variant", choices=["debug", "release", "all"], default="debug")
    args = parser.parse_args()

    if not shutil.which("rsvg-convert"):
        sys.exit("ERROR: rsvg-convert not on PATH (apt install librsvg2-bin / brew install librsvg)")

    variants = ["release", "debug"] if args.variant == "all" else [args.variant]
    with tempfile.TemporaryDirectory() as tmp:
        for variant in variants:
            generate(variant, Path(tmp))
    print("Done.")


if __name__ == "__main__":
    main()
