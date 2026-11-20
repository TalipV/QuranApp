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
| A3 | Eigene App-ID/eigener Name („QuranApp“), damit die App neben dem Original installierbar ist. | ✅ |

## Rezitationserkennung allgemein (Tilawa, offline)

| # | Wunsch | Status |
|---|--------|--------|
| R1 | Live-Rezitation im Mushaf-Leser: aktuelle Aya und aktuelles Wort markieren, Seite blättert mit. | ✅ |
| R2 | Eigener Rezitationsmodus (unten rechts in der Audioleiste ein/aus), getrennt von der Wiedergabe. | ✅ |
| R3 | Ladefortschritt des Modells sichtbar (Prozent); Start während des Ladens wird nachgeholt. | ✅ |
| R4 | Modell nur einmal laden – Suren-Liste und Leser teilen sich eine Engine. | ✅ |
| R5 | Hifẓ-Modus: noch nicht rezitierte Ayat ausblenden. | ✅ |

## Korrekturmodus (Fehlererkennung)

| # | Wunsch | Status |
|---|--------|--------|
| K1 | Fehler rot markieren; Hinweis nennt Aya, Wortposition und den Wortlaut. | ✅ |
| K2 | Strenge Prüfung: nicht gehörte Wörter einer Aya als „Wort ausgelassen“ melden. | ✅ |
| K3 | **Nach Pause frisch erkennen:** Beim Fortsetzen nicht blind die zuletzt erkannte Aya erwarten. Die Stelle wird neu gesucht (die letzte Aya ist nur ein Hinweis); kein Fehler, nur weil man schon in der nächsten Aya weiterliest. | 🧪 |
| K4 | **Sichtbar machen, wenn die Auswertung hinterherhängt:** Wenn die Erkennung nicht mitkommt, muss das klar angezeigt werden (Text + Animation), statt still auf einem Wort zu verharren und später viele Wörter weiterzuspringen. | 🧪 |
| K5 | **Automatisch umblättern** am Ende der letzten Aya einer Seite – nicht erst, wenn die nächste Aya erkannt ist. | 🧪 |

**Umsetzung K3:** Die App schickt beim Fortsetzen `relocate: true`. Die Brücke
(`recitation-web/src/main.ts`) setzt dann keine erwartete Passage, sondern nur einen Suchhinweis
(`engine.setHint`). Erst beim ersten Treffer gilt die Passage ab der gefundenen Aya bis zum Surenende.
Die ersten Wörter, die zum Wiederfinden nötig sind, werden dabei nicht auf Fehler geprüft.

**Umsetzung K4:** Es gibt zwei Signale. Beide färben den Status in der Rezitationsleiste rot und
zeigen einen laufenden Balken:
- *Rückstand:* Die Brücke meldet, wie viele Sekunden Audio noch nicht ausgewertet sind
  (`{type:"backlog"}`). Ab ~1 s erscheint „Wertet aus … (n s im Rückstand)“.
- *Kein Fortschritt:* Die App zählt hörbare Sprache (Mikrofonpegel) seit dem letzten neu erkannten
  Wort. Ab ~2 s (≈ 4–6 Wörter) erscheint „Kommt nicht mit – seit n s Rezitation kein Wort erkannt“.

**Umsetzung K5:** Erreicht `word_progress` das letzte Wort einer Aya und liegt die nächste Aya auf
einer anderen Seite, wird sofort zu dieser Seite geblättert.
Offen bleibt: Ayat, die selbst über einen Seitenumbruch gehen, blättern erst mit der nächsten Aya.

## „Stelle per Rezitation finden“ (Übersichtsseite)

| # | Wunsch | Status |
|---|--------|--------|
| F1 | Mikrofon-Knopf in der Suren-Liste: rezitierte Stelle erkennen und dorthin springen. | ✅ |
| F2 | **Sichtbares Feedback im Dialog:** Es muss klar sein, wann er bereit ist zuzuhören, wann er Sprache aufnimmt und wann er sie verarbeitet. Gelöst mit einem animierten Mikrofon: grau = lädt, Pulsieren = bereit, Ringe nach Lautstärke = hört zu, drehender Bogen + Balken = wertet aus, grünes Häkchen = gefunden. Dazu Statustext und die vermutete Stelle. | 🧪 |
