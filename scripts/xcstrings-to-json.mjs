// Converts the iOS string catalog (Localizable.xcstrings) into one JSON map per language for the Android app.
// Usage: node scripts/xcstrings-to-json.mjs <path/to/Localizable.xcstrings> [outDir]
// Keys are the Spanish source text, exactly as in iOS (`tr("…")`). Format specifiers become Java ones:
// %@ -> %s, %lld / %ld / %d -> %d, %1$@ -> %1$s. Output: <outDir>/<lang>.json (default app/src/main/assets/i18n).
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const [, , input, outDir = 'app/src/main/assets/i18n'] = process.argv;
if (!input) { console.error('usage: node scripts/xcstrings-to-json.mjs <Localizable.xcstrings> [outDir]'); process.exit(1); }

const catalog = JSON.parse(readFileSync(input, 'utf8'));
const langs = ['es', 'en', 'de', 'fr'];
const out = Object.fromEntries(langs.map((l) => [l, {}]));
// Android has no Face ID: the biometric prompt covers fingerprint and face.
const biometric = { es: "biometría", en: "biometrics", de: "Biometrie", fr: "biométrie" };
const toJava = (s) => s.replace(/%(\d+\$)?@/g, '%$1s').replace(/%(\d+\$)?l{1,2}d/g, '%$1d').replace(/%(\d+\$)?lu/g, '%$1d');

let missing = 0;
for (const [key, entry] of Object.entries(catalog.strings)) {
  if (!key) continue;
  const locs = entry.localizations ?? {};
  for (const l of langs) {
    const value = locs[l]?.stringUnit?.value;
    if (value === undefined) {
      if (l !== 'es') missing++;
      if (l === 'es') out.es[key] = toJava(key).replaceAll("Face ID", biometric.es);
      continue;
    }
    out[l][key] = toJava(value).replaceAll("Face ID", biometric[l]);
  }
  // Keys are looked up by their Spanish source text with the specifiers already converted.
  const javaKey = toJava(key);
  if (javaKey !== key) for (const l of langs) if (key in out[l]) { out[l][javaKey] = out[l][key]; delete out[l][key]; }
}
// Strings that only exist on Android (iOS gets them from system buttons).
const extras = JSON.parse(readFileSync(new URL("./android-extra-strings.json", import.meta.url), "utf8"));
for (const [key, tr] of Object.entries(extras)) for (const l of langs) out[l][key] = tr[l] ?? key;
mkdirSync(outDir, { recursive: true });
for (const l of langs) writeFileSync(`${outDir}/${l}.json`, JSON.stringify(out[l], null, 1) + '\n', 'utf8');
console.log(`keys: ${Object.keys(out.es).length}; missing translations (non-es): ${missing}`);
