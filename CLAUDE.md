# Hinweise für KI-Sitzungen

## Projekt
Android-App (Kotlin) für die Schichtplanung einer kleinen Filiale mit ca. 10 Mitarbeitern.
Zielnutzerin arbeitet lieber analog: Die App muss spürbar Arbeit abnehmen und darf kaum Tipparbeit verlangen.
Fachliche Regeln (allgemein): [docs/regeln.md](docs/regeln.md). Konkrete Ladendaten liegen bewusst nicht im Repo.

## Zusammenarbeit
- Sprache: Deutsch. Direkt, ehrlich, knapp.
- Code immer als vollständige Datei liefern, keine Schnipsel.
- Jede Regel des Planers bekommt automatische Tests. Manuelles Testen möglichst vermeiden.

## Datenschutz: Dieses Repo ist öffentlich
- Keine echten Namen, Fotos, Filialnummern, Firmen- oder Markennamen, Umsätze.
- Testdaten nur mit erfundenen Vornamen.
- Ladenspezifische Werte (Öffnungszeiten, Schichten, Besetzung) sind in der App einstellbar, nicht fest im Code. Im Code höchstens als änderbare Beispiel-Vorgabe.
- Signierschlüssel und Passwörter nur in GitHub Secrets, nie im Repo. Der Schlüssel bleibt für immer derselbe, sonst brechen Updates.

## Technik (V1)
- Kotlin, Android. Daten lokal (Datenbank auf dem Gerät) plus Export/Import als Sicherungsdatei.
- Planung mit Constraint-Solver, offline. Kandidaten: Choco (reines Java), OR-Tools CP-SAT (nativ), Timefold. Noch nicht entschieden.
- PDF-Export im Layout des gewohnten Papier-Wochenblatts.
- Updates: GitHub Actions baut bei jedem Release eine signierte APK. Die App prüft beim Start die GitHub Releases API, zeigt "Update verfügbar", lädt die APK und öffnet die Installation.
- Application-ID: `de.reichimd.schichtplaner` (festgelegt 03.11.2026). Darf nie mehr geändert werden.
- Signierung: Release-Keystore liegt NICHT im Repo. GitHub Secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (= `schichtplaner`), `SIGNING_KEY_PASSWORD`.
