# recitation-web

Dünne Brücke zwischen der App und [`@tilawa/core`](https://github.com/yazinsai/tilawa) (Rezitationserkennung, offline).
Läuft in einer unsichtbaren WebView (`TilawaEngine.kt`).

- `npm ci && npm run build` → schreibt Bundle, WASM, Modell und Korpus nach `app/src/main/assets/recitation/` (nicht eingecheckt).
- **Tilawa aktualisieren:** Version von `@tilawa/core` in `package.json` anheben (`npm install @tilawa/core@latest`). Neues Modell/Korpus: URLs in `assets.json` ändern.
- `onnxruntime-web` ist auf die Version gepinnt, die die Tilawa-Demo verwendet.
- Lizenz: Code MIT; Modell und Phonem-Korpus NPL-1.2 (nicht kommerziell, Share-alike).
