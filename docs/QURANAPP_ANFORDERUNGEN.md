# QuranApp – Funktionswünsche und Anforderungen

Sammlung der Wünsche des Projektinhabers für diesen Fork (Basis: quran_android).
Neue Wünsche hier eintragen, mit Status und, wo nötig, den Details der Umsetzung.
Fertige APK nach jedem Push auf `main`: <https://github.com/TalipV/QuranApp/releases/tag/latest>

Status: ✅ umgesetzt · 🧪 umgesetzt, noch nicht am Gerät bestätigt · ⏳ offen

---

## Auslieferung

| # | Wunsch | Status |
|---|--------|--------|
| A1 | Änderungen direkt auf `main`; jede Änderung baut automatisch eine APK. | ✅ |
| A2 | Immer derselbe Link mit der aktuellen APK (Release `latest`, alte Releases werden entfernt). | ✅ |
| A4 | Versionsnummern und APK-Namen wie bei PrayerTimeEngine: eigene Version `0.x.y` (von Claude pro Push erhöht), versionCode aus der Build-Nummer, APK `QuranApp-<Version>.apk`, Release-Titel `<Version> (Build N, Datum)`. Regeln in `CLAUDE.md`. | ✅ |
| A3 | Eigene App-ID/eigener Name („QuranApp“), damit die App neben dem Original installierbar ist. | ✅ |

## Rezitationserkennung allgemein (Tilawa, offline)

| # | Wunsch | Status |
|---|--------|--------|
| R1 | Live-Rezitation im Mushaf-Leser: aktuelle Aya und aktuelles Wort markieren, Seite blättert mit. | ✅ |
| R2 | Eigener Rezitationsmodus (unten rechts in der Audioleiste ein/aus), getrennt von der Wiedergabe. | ✅ |
| R3 | Ladefortschritt des Modells sichtbar (Prozent); Start während des Ladens wird nachgeholt. | ✅ |
| R4 | Modell nur einmal laden – Suren-Liste und Leser teilen sich eine Engine. | ✅ |
| R5 | Hifẓ-Modus: noch nicht rezitierte Ayat ausblenden. | ✅ |
| R6 | **Erkennung verbessern: Umstieg auf Tilawa 1.0.0-rc.0** (neue Engine, verfolgt Wort für Wort, Modell `lite` mit 25 MB). Die Vokalprüfung (Ḥaraka), die Erkennung von Wiederholungen und „Aya nicht verständlich“ fallen vorerst weg, weil 1.0 sie noch nicht kann. Der Inhaber hat zugestimmt, zuerst ohne sie zu testen. | 🧪 |
| R8 | Modell `full` (68 MB) statt `lite`: Das Zielgerät (Galaxy S24 Ultra) hat genug Leistung, Genauigkeit geht vor Größe. | 🧪 |
| R9 | Tilawa auf 1.0.0-rc.1 aktualisiert (gleiche Modelle; Engine-Fehlerbehebungen: aʿūḏu billāh wird nicht mehr als 2:67/16:98 erkannt, weniger Fehlalarme bei Ikhfāʾ/Iqlāb und schneller Rezitation, keine doppelte „Stelle übersprungen“-Meldung). Unsere eigene Einleitungs-Erkennung bleibt als zweite Sicherung. | 🧪 |
| R7 | Vokalprüfung (Ḥaraka) zurückholen, sobald Tilawa sie wieder unterstützt (`capabilities.unsupported` enthält derzeit `short_vowel`). | ⏳ |

**Umsetzung R6:** Die Brücke (`recitation-web/src/engine.ts`) übersetzt die neuen Tilawa-Ereignisse in die
bisherigen Nachrichten der App. Die Android-Seite bleibt dadurch fast unverändert.
- Korrekturmodus: `startMemorization` (Passage ab Start-Aya bis Surenende).
- Nur Mitlesen und „Stelle finden“: `startFreeRecitation`.
- Fehler gelten als bestätigt, sobald das nächste Wort beginnt.
- Fehler vor der ersten sicher erkannten Stelle werden verworfen. Damit gilt auch K3 (Fortsetzen nach Pause).
- `npm run smoke` prüft das alles im CI mit echtem Modell und echter Rezitation.

## Korrekturmodus (Fehlererkennung)

| # | Wunsch | Status |
|---|--------|--------|
| K1 | Fehler rot markieren; Hinweis nennt Aya, Wortposition und den Wortlaut. | ✅ |
| K2 | Strenge Prüfung: nicht gehörte Wörter einer Aya als „Wort ausgelassen“ melden. | ✅ |
| K3 | **Nach Pause frisch erkennen:** Beim Fortsetzen nicht blind die zuletzt erkannte Aya erwarten. Die Stelle wird neu gesucht (die letzte Aya ist nur ein Hinweis); kein Fehler, nur weil man schon in der nächsten Aya weiterliest. | 🧪 |
| K6 | **Einleitung nicht als Quranstelle nehmen:** Aʿūḏu billāhi mina š-šayṭāni r-raǧīm und die Basmala vor der Rezitation dürfen weder im Korrekturmodus noch bei „Stelle finden“ als gesuchte Stelle gelten. (Vorher wurde aʿūḏu billāh als 2:67 erkannt.) | 🧪 |
| K4 | **Sichtbar machen, wenn die Auswertung hinterherhängt:** Wenn die Erkennung nicht mitkommt, muss das klar angezeigt werden (Text + Animation), statt still auf einem Wort zu verharren und später viele Wörter weiterzuspringen. | 🧪 |
| K5 | **Automatisch umblättern** am Ende der letzten Aya einer Seite – nicht erst, wenn die nächste Aya erkannt ist. | 🧪 |

**Umsetzung K3:** Seit Tilawa 1.x (R6) gilt: Fehler vor der ersten sicher erkannten Stelle einer Sitzung
werden verworfen. Wer nach einer Pause schon in der nächsten Aya weiterliest, bekommt also keinen
Fehler für die Wörter davor.

**Umsetzung K4:** Die Brücke meldet, wie viele Sekunden Audio noch nicht ausgewertet sind
(`{type:"backlog"}`). Ab ~1 s Rückstand wird der Status in der Rezitationsleiste rot, mit laufendem
Balken und „Wertet aus … (n s im Rückstand)“.
Die frühere Warnung „Kommt nicht mit – seit n s kein Wort erkannt“ ist **entfernt**. Sie sprang auch
bei Stille bzw. Hintergrundgeräuschen an, und mit Tilawa 1.x ist sie laut Inhaber nicht mehr nötig.

**Umsetzung K6** (`recitation-web/src/engine.ts`):
- *Stelle finden / nur Mitlesen:* Eine neue Stelle zählt erst, wenn die Rezitation dort sicher
  2 Wörter fortlaufend weitergeht. Danach gilt sie rückwirkend ab ihrem Beginn. Einleitungen gehen
  nie in 2:67 bzw. 1:1 weiter und werden so nie bestätigt.
- *Korrekturmodus:* Es zählen nur Stellen innerhalb der Passage.
- Während der Einleitung wird auch nichts *vermutet*: Treffer in Ayat, die dieselben Wörter
  enthalten (2:67, 11:47, 19:18, 16:98 … bzw. 1:1, 27:30), erscheinen nicht als „Vermutlich: …“.
  Der Dialog „Stelle finden“ zeigt stattdessen „Aʿūḏu / Basmala erkannt – rezitiere weiter …“.
- Getestet mit echter Rezitation (aʿūḏu + Basmala + Yā-Sīn, Basmala + Al-Baqara) in `npm run smoke`.
- Bekannte Grenze: Einzelbuchstaben-Ayat wie 36:1 „Yā-Sīn“ oder 2:1 „Alif-Lām-Mīm“ erkennt Tilawa
  allein nicht. Die Stelle wird dann ab der folgenden Aya erkannt.

**Umsetzung K5:** Erreicht `word_progress` das letzte Wort einer Aya und liegt die nächste Aya auf
einer anderen Seite, wird sofort zu dieser Seite geblättert.
Offen bleibt: Ayat, die selbst über einen Seitenumbruch gehen, blättern erst mit der nächsten Aya.

## „Stelle per Rezitation finden“ (Übersichtsseite)

| # | Wunsch | Status |
|---|--------|--------|
| F1 | Mikrofon-Knopf in der Suren-Liste: rezitierte Stelle erkennen und dorthin springen. | ✅ |
| F3 | **Nach „Stelle finden“ direkt weitermachen:** Nach dem Sprung zur gefundenen Stelle startet sofort der Rezitationsmodus (mit Korrektur) ab dieser Aya und verfolgt Wort für Wort weiter. | 🧪 |
| F2 | **Sichtbares Feedback im Dialog:** Es muss klar sein, wann er bereit ist zuzuhören, wann er Sprache aufnimmt und wann er sie verarbeitet. Gelöst mit einem animierten Mikrofon: grau = lädt, Pulsieren = bereit, Ringe nach Lautstärke = hört zu, drehender Bogen + Balken = wertet aus, grünes Häkchen = gefunden. Dazu Statustext und die vermutete Stelle. | 🧪 |

## Fortschritts-Tracker (Laufende Rezitationen)

| # | Wunsch | Status |
|---|--------|--------|
| L1 | Eine Sure (z. B. Al-Kahf) entspannt in mehreren Etappen lesen: Stelle markieren, beim nächsten Öffnen der App sofort dorthin. | 🧪 |
| L2 | **Benannte Tracker:** Beim Markieren fragt die App, in welchem Tracker gemerkt wird. Anfangs gibt es nur „Standard“, über „＋“ lassen sich neue anlegen (z. B. „Suratul Kahf“, „Runde mit Freunden“, „Private Khatma“). | 🧪 |

**Umsetzung (seit 0.3.0):**
- *Markieren:* Im Leser lange auf eine Aya tippen, in der Aya-Leiste das Fähnchen wählen. Dann erscheint der Dialog
  „Fortschritt merken in …“: Liste der Tracker (unter dem Namen klein die gemerkte Stelle), dazu „＋ Neuer Tracker“
  mit Namenseingabe. Die Aya wird im gewählten Tracker gemerkt.
- *Tracker:* Ein Tracker merkt sich eine Stelle, egal in welcher Sure. „Standard“ gibt es immer und lässt sich nicht löschen.
- *Startseite:* Je Tracker mit gemerkter Stelle eine Karte, z. B. „Runde mit Freunden – Al-Baqara, Aya 142 · Seite 22 · vor 3 Tagen“.
  - Antippen springt zur Aya (hervorgehoben).
  - „⋮“ bietet: Runde abschließen (zählt mit), Stelle zurücksetzen, Umbenennen, Tracker löschen.
- *Übernahme aus 0.2.0:* Die damals je Sure gemerkten Rezitationen werden einmalig zu Trackern mit dem Namen der Sure.
- *Geändert gegenüber 0.2.0:* Kein automatisches Abschließen an der letzten Aya einer Sure mehr, weil ein Tracker
  nicht an eine Sure gebunden ist. Abgeschlossen wird über „⋮ → Runde abschließen“.
- *Speicherung:* lokal (SharedPreferences `quranapp_reading_progress`, Schlüssel `trackers`, JSON).
- *Code:* `app/.../quranapp/ReadingProgressStore.kt`, `ReadingProgressDialogs.kt`, `ReadingProgressHomeSection.kt`;
  Menüpunkt `cab_mark_progress` in `common/toolbar`.
- *Idee für später:* Im Rezitationsmodus beim Pausieren die erkannte Stelle automatisch in einem Tracker merken.
