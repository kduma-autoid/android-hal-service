# HAL Service — brand

The mark is a **chip**: a rounded body with three pins on each side and the letter **H** cut out of it.
On app icons it sits, solid white, on an indigo gradient tile. The style matches the other product
icons it ships next to: a rounded tile with a diagonal gradient, one solid white symbol about two
thirds of the tile, details cut out rather than outlined, no second colour.

## Files

| File | Use |
|------|-----|
| `hal-icon.svg` | App icon: tile + mark (512 px). Source for every app icon. |
| `hal-plugin-icon.svg` | Plugin bundle apps: the same chip with a **puzzle piece** cut out instead of the H. |
| `hal-mark.svg`, `hal-plugin-mark.svg` | The bare mark, one colour (`currentColor`, indigo when standalone): tray / menu bar, monochrome, small print. |
| `hal-logo.svg`, `hal-logo-dark.svg` | Icon + "HAL Service" wordmark for light / dark backgrounds (README, docs). |
| `social-preview.svg` | GitHub social preview, 1280×640. |
| `png/` | Rendered PNGs of the above (generated, see below). |

The mark is drawn on the 108-unit grid of an Android adaptive icon (it spans 28..80, centre 54), so
the same path data is used in the SVGs and in the Android vector drawables.

## Colour

| Role | Value |
|------|-------|
| Tile gradient, 155° (light top-left → dark bottom-right) | `#7C8CFF` → `#4C5CF5` (50 %) → `#2F33C9` |
| Brand indigo (single colour) | `#4C5CF5` |
| Mark on the tile | `#FFFFFF` |
| DEV band (development builds) | `#FFD43B`, lettering `#1A1A1A` |
| Text on light / dark | `#14181D` / `#FFFFFF`; secondary `#4A5562` / `#B6C0CC` |

Indigo is chosen to stay apart from the neighbouring products' hues (blues, purples, orange).

## Rules

- **One icon on every platform and build.** Platforms differ only in the tile shape their system asks
  for: Android adaptive icon (the launcher draws the mask; plus a monochrome layer for themed icons),
  macOS (824/1024 tile with shadow), Windows and Linux (tile). The platform is not marked on the icon —
  on download pages name it in text next to the icon.
- **Builds:** Sunmi builds use the same icon; they are told apart by the app name ("HAL Service
  (Sunmi)"). Development builds add the yellow **DEV** band across the lower edge, because a
  development and a stable build can be installed side by side.
- **Desktop services** run in the background: their main icon is the bare mark in the menu bar / tray
  (`hal-mark.svg`, black or white per the system bar).
- Minimum size: tile 16 px, bare mark 12 px. Keep clear space of a quarter of the tile around the logo.
- Don't outline the mark, add a second colour, recolour the H, or place the white mark on light
  backgrounds (use `hal-mark.svg` in indigo or black there).

## Where it is used

- `service/hal-service/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml` —
  adaptive icon (vector); `dev_ribbon.xml` — the DEV band of the `*Development` flavours.
- `plugins/**/plugin-*-bundle/src/main/res/drawable/ic_launcher_*.xml` — plugin bundles (puzzle variant).
- `mipmap-*dpi/ic_launcher*.webp` and `ic_launcher-playstore.png` in those modules — generated rasters
  (legacy icons for API 24–25, Play Store).
- `clients/ts/example/public/favicon.svg` — demo favicon and navigation-bar logo (a copy of `hal-icon.svg`).

## Regenerating the rasters

After changing the mark or the palette, update the vector drawables by hand (same path data) and run:

```bash
node scripts/brand/export-icons.mjs
```

It needs Google Chrome (headless SVG rendering) and `cwebp` (`brew install webp`), and rewrites the
legacy launcher icons and Play Store icons of hal-service and the plugin bundles, `branding/png/`, and
the demo favicon. The social preview PNG is uploaded by hand in the repository settings.
