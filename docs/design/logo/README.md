# FiberSocial logo assets

Source vector art for the app logo and launcher icon — the purple "FS"
yarn-ball-with-needles monogram (brand purple `#7950f2`).

| File | Use |
|------|-----|
| `FiberSocialLogo_clearbackground.svg` | Transparent background — the in-app logo (login screen, feed top bar) and the launcher-icon foreground layer. |
| `FiberSocialLogo_whitebackground.svg`  | Logo on white — light-context marketing/preview use. |
| `FiberSocialLogo_darkmode.svg`         | Logo on dark — dark-context use. |
| `ic_launcher-playstore.png`            | 512×512 Play Store listing icon (lavender `#EEE9FF` background, full square — the Play Store applies its own mask). |
| `feature-graphic.svg` / `feature-graphic.png` | 1024×500 Play Store feature graphic (store listing banner). The PNG is the upload-ready asset (24-bit, no alpha, rendered via `rsvg-convert`); the SVG is the editable source — re-render with `rsvg-convert -w 1024 -h 500 --format=png feature-graphic.svg -o feature-graphic.png` if the copy or layout changes. |

## Launcher icon

Built as an adaptive icon (`res/mipmap-anydpi-v26/ic_launcher.xml`):
a lavender `#EEE9FF` background (`@color/ic_launcher_background`) behind the
transparent logo foreground (`res/mipmap-*/ic_launcher_foreground.png`, the
logo scaled to ~62 % of the 108 dp layer so it sits inside the mask safe zone).

The foreground/logo PNGs are raster because the monogram's "FS" is set in the
Excalifont typeface embedded in the SVG — Android `VectorDrawable` has no font
support, so the SVGs are rendered to PNG at each density instead. Regenerate
from the SVGs above if the mark changes.
