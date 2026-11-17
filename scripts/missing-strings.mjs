// Lists the tr("…") keys used in the Kotlin sources that no catalog (iOS export + Android extras) defines.
// Usage: node scripts/missing-strings.mjs
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const es = JSON.parse(readFileSync('app/src/main/assets/i18n/es.json', 'utf8'));
const extra = { ...JSON.parse(readFileSync('scripts/android-extra-strings.json', 'utf8')), ...JSON.parse(readFileSync('scripts/android-extra-recording.json', 'utf8')) };
const have = new Set([...Object.keys(es), ...Object.keys(extra)]);
const missing = new Set();
const re = /(?:\btr|\.tr|statusLineKey|detailKey)\(\s*"((?:[^"\\]|\\.)*)"/g;

function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path);
    else if (path.endsWith('.kt')) {
      const text = readFileSync(path, 'utf8');
      for (const m of text.matchAll(re)) {
        const key = m[1].replace(/\\"/g, '"').replace(/\\n/g, '\n');
        if (!have.has(key)) missing.add(key);
      }
    }
  }
}
for (const dir of ['app/src/main', 'core/designsystem/src', 'core/ai/src/main']) walk(dir);
for (const key of missing) console.log(JSON.stringify(key));
console.log(`missing: ${missing.size}`);
