#!/usr/bin/env node
/**
 * get-icon.js — Download a Material Symbols icon, convert to Android vector drawable
 *
 * Usage:
 *   node tools/get-icon.js <icon_name> [style]
 *
 * Styles: rounded (default), outlined, sharp
 *
 * Output: app/src/main/res/drawable/ic_<icon_name>.xml
 *
 * Browse icons at: https://fonts.google.com/icons
 * Names are lowercase_with_underscores (e.g. alarm, bar_chart, check_circle)
 *
 * Examples:
 *   node tools/get-icon.js alarm
 *   node tools/get-icon.js bar_chart rounded
 *   node tools/get-icon.js sync outlined
 */

const https = require('https');
const fs    = require('fs');
const path  = require('path');

const [,, iconName, style = 'rounded'] = process.argv;

if (!iconName) {
  console.log('Usage: node tools/get-icon.js <icon_name> [rounded|outlined|sharp]');
  console.log('');
  console.log('Examples:');
  console.log('  node tools/get-icon.js alarm');
  console.log('  node tools/get-icon.js bar_chart rounded');
  console.log('  node tools/get-icon.js sync outlined');
  console.log('');
  console.log('Browse icons: https://fonts.google.com/icons');
  process.exit(1);
}

const outputDir = path.resolve(__dirname, '../app/src/main/res/drawable');
const outputXml = path.join(outputDir, `ic_${iconName}.xml`);
const url = `https://fonts.gstatic.com/s/i/short-term/release/materialsymbols${style}/${iconName}/default/24px.svg`;

function download(url) {
  return new Promise((resolve, reject) => {
    https.get(url, res => {
      if (res.statusCode !== 200) {
        reject(new Error(`HTTP ${res.statusCode} — icon '${iconName}' not found in style '${style}'`));
        return;
      }
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    }).on('error', reject);
  });
}

function svgToVector(svg) {
  // --- Parse viewBox ---
  const vbMatch = svg.match(/viewBox="([^"]+)"/);
  if (!vbMatch) throw new Error('No viewBox found in SVG');
  const [minX, minY, vbW, vbH] = vbMatch[1].trim().split(/\s+/).map(Number);

  // --- Extract all path `d` attributes ---
  const pathRe = /\bd="([^"]+)"/g;
  const paths = [];
  let m;
  while ((m = pathRe.exec(svg)) !== null) paths.push(m[1]);
  if (paths.length === 0) throw new Error('No path data found in SVG');

  // --- Build Android vector XML ---
  // Material Symbols use viewBox="0 -960 960 960": minY is -960.
  // Android vector viewports start at (0,0), so we must translate by (-minX, -minY)
  // to shift the paths into the visible canvas.
  const needsTranslate = minX !== 0 || minY !== 0;
  const tx = -minX;
  const ty = -minY;

  const indent = needsTranslate ? '        ' : '    ';
  const pathsXml = paths.map(d =>
    `${indent}<path\n${indent}    android:fillColor="#000000"\n${indent}    android:pathData="${d}"/>`
  ).join('\n');

  let inner;
  if (needsTranslate) {
    inner =
      `    <group android:translateX="${tx}" android:translateY="${ty}">\n` +
      pathsXml + '\n' +
      `    </group>`;
  } else {
    inner = pathsXml;
  }

  return (
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n` +
    `    android:width="24dp"\n` +
    `    android:height="24dp"\n` +
    `    android:viewportWidth="${vbW}"\n` +
    `    android:viewportHeight="${vbH}">\n` +
    inner + '\n' +
    `</vector>\n`
  );
}

(async () => {
  try {
    console.log(`⬇  Downloading '${iconName}' (${style})...`);
    const svg = await download(url);

    console.log('🔄 Converting SVG → Android vector drawable...');
    const xml = svgToVector(svg);

    fs.writeFileSync(outputXml, xml);

    console.log('');
    console.log(`✅ Done: app/src/main/res/drawable/ic_${iconName}.xml`);
    console.log('');
    console.log('   Use in XML layout:');
    console.log(`     <ImageView`);
    console.log(`         android:layout_width="24dp"`);
    console.log(`         android:layout_height="24dp"`);
    console.log(`         android:src="@drawable/ic_${iconName}"`);
    console.log(`         android:tint="@color/colorAccent" />`);
  } catch (err) {
    console.error('');
    console.error(`❌ ${err.message}`);
    console.error('   Check the exact name at: https://fonts.google.com/icons');
    process.exit(1);
  }
})();
