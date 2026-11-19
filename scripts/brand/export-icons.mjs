#!/usr/bin/env node
// Renders the raster icon formats from the vector sources in branding/ and writes them into the
// Android modules (legacy launcher icons for API 24–25, Play Store icons) and branding/png/.
//
// The adaptive icons themselves (API 26+) are vector drawables written by hand and need no export;
// this script only covers what has to be a bitmap. Run it after changing the mark or the palette:
//
//     node scripts/brand/export-icons.mjs
//
// Needs Google Chrome (headless, for SVG → PNG with transparency) and cwebp (`brew install webp`).
// Override the Chrome path with CHROME=/path/to/chrome.

import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const chrome = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

/** Brand gradient: 155°, light top-left to dark bottom-right. Keep in sync with branding/README.md. */
const GRADIENT = 'linear-gradient(155deg, #7C8CFF 0%, #4C5CF5 50%, #2F33C9 100%)';

const marks = {
  app: readFileSync(join(root, 'branding/hal-mark.svg'), 'utf8'),
  plugin: readFileSync(join(root, 'branding/hal-plugin-mark.svg'), 'utf8'),
};

/** Android launcher densities: legacy icons are 48 dp. */
const DENSITIES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };

/**
 * Tile shapes. `markScale` is the mark's box (grid 26..82) relative to the tile:
 * - tile: the mark on the 16..92 part of the grid, as in branding/hal-icon.svg → 56/76;
 * - adaptive: the visible 18..90 part of an adaptive icon → 56/72.
 */
const SHAPES = {
  legacy: { radius: '20%', margin: 0.04, markScale: 56 / 76 },
  round: { radius: '50%', margin: 0.04, markScale: 56 / 72 },
  playstore: { radius: '0', margin: 0, markScale: 56 / 76 },
  tile: { radius: '20%', margin: 0, markScale: 56 / 76 },
};

const work = mkdtempSync(join(tmpdir(), 'hal-icons-'));

function whiteMark(svg) {
  return svg.replace(/<\?xml[^>]*>/, '').replace(/<!--[\s\S]*?-->/g, '').replace(/color="#[0-9A-Fa-f]+"/, 'color="#FFFFFF"');
}

/** Screenshots `html` at width×height into a PNG at `out`, transparent outside what is drawn. */
function render(html, width, height, out) {
  const page = join(work, `page-${Math.random().toString(36).slice(2)}.html`);
  writeFileSync(page, `<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;background:transparent;overflow:hidden}</style></head><body>${html}</body></html>`);
  mkdirSync(dirname(out), { recursive: true });
  execFileSync(chrome, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
    '--default-background-color=00000000', `--window-size=${width},${height}`, `--screenshot=${out}`,
    `file://${page}`,
  ], { stdio: 'ignore' });
}

function iconHtml(kind, shape, size) {
  const s = SHAPES[shape];
  const margin = Math.round(size * s.margin);
  const mark = Math.round((size - 2 * margin) * s.markScale);
  return `<div style="width:${size}px;height:${size}px;box-sizing:border-box;padding:${margin}px">`
    + `<div style="width:100%;height:100%;border-radius:${s.radius};overflow:hidden;background:${GRADIENT};display:flex;align-items:center;justify-content:center">`
    + `<div style="width:${mark}px;height:${mark}px;display:flex">${whiteMark(marks[kind]).replace('<svg ', '<svg style="width:100%;height:100%" ')}</div>`
    + '</div></div>';
}

function renderIcon(kind, shape, size, out) {
  render(iconHtml(kind, shape, size), size, size, out);
}

function toWebp(png, webp) {
  mkdirSync(dirname(webp), { recursive: true });
  execFileSync('cwebp', ['-quiet', '-lossless', png, '-o', webp]);
}

/** Legacy launcher icons + Play Store icon for one Android module (its `src/main`). */
function exportAndroid(kind, srcMain) {
  for (const [density, size] of Object.entries(DENSITIES)) {
    for (const [name, shape] of [['ic_launcher', 'legacy'], ['ic_launcher_round', 'round']]) {
      const png = join(work, `${kind}-${name}-${density}.png`);
      renderIcon(kind, shape, size, png);
      toWebp(png, join(srcMain, `res/mipmap-${density}/${name}.webp`));
    }
  }
  renderIcon(kind, 'playstore', 512, join(srcMain, 'ic_launcher-playstore.png'));
}

const pluginBundles = [
  'plugins/sunmi/plugin-sunmi-bundle',
  'plugins/sunmi/printerx/plugin-sunmi-printerx-bundle',
  'plugins/sunmi/sunmiperipher/plugin-sunmi-sunmiperipher-bundle',
  'plugins/sunmi/sunmiscannersdk/plugin-sunmi-sunmiscannersdk-bundle',
  'plugins/sunmi/tms/plugin-sunmi-tms-bundle',
];

try {
  exportAndroid('app', join(root, 'service/hal-service/src/main'));
  for (const bundle of pluginBundles) exportAndroid('plugin', join(root, bundle, 'src/main'));

  const png = join(root, 'branding/png');
  for (const size of [1024, 512, 192, 64]) {
    renderIcon('app', 'tile', size, join(png, `hal-icon-${size}.png`));
    renderIcon('plugin', 'tile', size, join(png, `hal-plugin-icon-${size}.png`));
  }
  const social = readFileSync(join(root, 'branding/social-preview.svg'), 'utf8').replace(/<\?xml[^>]*>/, '');
  render(social, 1280, 640, join(png, 'social-preview.png'));
  mkdirSync(join(root, 'clients/ts/example/public'), { recursive: true });
  copyFileSync(join(root, 'branding/hal-icon.svg'), join(root, 'clients/ts/example/public/favicon.svg'));

  console.log('Exported icons for hal-service, %d plugin bundles, branding/png and the demo favicon.', pluginBundles.length);
} finally {
  rmSync(work, { recursive: true, force: true });
}
