# QuranApp – Hinweise für Claude

Fork von quran_android mit eigener App-ID (`de.talipv.quranapp`) und Offline-Rezitationserkennung (Tilawa).
Funktionswünsche des Inhabers stehen mit Status in `docs/QURANAPP_ANFORDERUNGEN.md`. Neue Wünsche dort eintragen.

## Direkt auf `main` committen

Änderungen werden direkt auf `main` gepusht, ohne Pull Request. Jeder Push auf `main` baut eine APK.
Die APK liegt immer unter demselben Link: <https://github.com/TalipV/QuranApp/releases/tag/latest>.
Nach dem Push prüfen, ob der Build durchläuft (`.github/workflows/release-apk.yml`).

## Versionierung

Gleiches Schema wie im Repository TalipV/PrayerTimeEngine:

- **`versionName`** in `app/build.gradle.kts` ist die Version, die der Nutzer sieht, als
  `MAJOR.MINOR.PATCH` (Semantic Versioning). Die App ist noch nicht fertig, deshalb bleibt sie
  bei `0.x.y`. `1.0.0` kommt erst, wenn der Inhaber das sagt.
- **`versionCode`** wird nicht von Hand gepflegt. Das CI setzt ihn auf `4000 + github.run_number`,
  sodass jeder Build hochzählt und als Update installiert. Der Abstand 4000 hält ihn über dem
  `3650` der früheren Builds. Die `3650` in `build.gradle.kts` gilt nur für lokale Builds und bleibt so.
- **Die Version erhöht Claude.** Sobald eine Änderung an der App auf `main` landet, `versionName`
  im selben Commit erhöhen, einmal pro Push, auf der höchsten Stufe, die eine der gepushten
  Änderungen braucht:
  - **PATCH** (`0.3.1` → `0.3.2`): Fehlerbehebungen, Text- oder Übersetzungskorrekturen, kleine UI-Verbesserungen.
  - **MINOR** (`0.3.2` → `0.4.0`): neue Funktionen, spürbar geändertes Verhalten (auch der Erkennung),
    und Änderungen, die gespeicherte Daten oder Einstellungen brechen.
  - **MAJOR**: nur auf Wunsch des Inhabers.
  - **Keine Erhöhung**: Änderungen, die nicht in der App ankommen (Tests, Doku, CI, Refactoring ohne Verhaltensänderung).
- **Im Zweifel** (PATCH oder MINOR, erhöhen oder nicht) vor dem Commit den Inhaber fragen.
- Die neue Version im Commit-Text nennen (z. B. `Version 0.4.0.`).

## APK und Release

- Dateiname: `QuranApp-<versionName>.apk` (z. B. `QuranApp-0.1.0.apk`).
- Release-Titel: `<versionName> (Build <Nummer>, <Datum Uhrzeit MEZ/MESZ>)`.
- Das Release `latest` wird bei jedem Build neu angelegt (als Vorabversion markiert).

## Rezitationserkennung

- Brücke und Tests liegen in `recitation-web/` (siehe dortiges README).
- `npm run smoke` prüft die Engine mit echtem Modell und echter Rezitation und läuft im CI vor dem APK-Build.
- Für lokale Tests hinter dem Agent-Proxy `NODE_USE_ENV_PROXY=1` setzen. Die Umgebung „QuranApp“ erlaubt
  `tilawa.dev` (Modell) und `everyayah.com` (Testaudio).
