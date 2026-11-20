# recitation-web

Dünne Brücke zwischen der App und [`@tilawa/core`](https://github.com/yazinsai/tilawa) 1.x (Rezitationserkennung, offline).
Läuft in einer unsichtbaren WebView (`TilawaEngine.kt`).

- `npm ci && npm run build` → schreibt Bundle, WASM, Modell und Quran-Referenz nach `app/src/main/assets/recitation/` (nicht eingecheckt).
  Das Modell kommt von `tilawa.dev` und wird gegen die sha256 aus der Tilawa-Registry geprüft.
- `npm run smoke` → prüft die Engine in Node mit dem echten Modell: Stille, freie Rezitation, Korrekturmodus,
  ausgelassene Aya (Testaudio: Al-Fātiḥa von everyayah.com; ohne Netz/ffmpeg wird dieser Teil übersprungen).
  Läuft auch im CI vor dem APK-Build.
- Hinter dem Agent-Proxy lokal: `NODE_USE_ENV_PROXY=1 npm run build`.

## Aufbau

- `src/engine.ts`: Engine bauen und Tilawa-Ereignisse (`position`, `checker`) in die Nachrichten der App übersetzen
  (`verse_match`, `verse_candidate`, `word_progress`, `correction_note`). Ändert Tilawa die API, wird nur hier angepasst.
- `src/main.ts`: WebView-Seite (Laden, Sitzungen, Audio, Rückstandsmeldung).
- `src/model.json`: Modellstufe – `lite` (25 MB, für Handys ohne WebGPU) oder `full` (68 MB).

## Tilawa aktualisieren

Version von `@tilawa/core` in `package.json` anheben, `npm install`, dann `npm run build && npm run smoke`.

## Lizenz

Code MIT. Modell und Referenz von Tilawa 1.x sind nicht mehr NPL-1.2 (siehe `node_modules/@tilawa/core/NOTICE.md`).
