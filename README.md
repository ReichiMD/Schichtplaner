# Schichtplaner

Android-App, die einer Filialleitung den Wochen-Schichtplan abnimmt. Team, Arbeitszeiten, Schichten sowie Urlaub und Frei-Wünsche (Wünsche-Kalender) werden in der App eingestellt. Alle Daten bleiben lokal auf dem Handy. Ins Internet geht die App nur für den Update-Check bei GitHub (dabei sieht GitHub die IP-Adresse und die App-Version).

## Aufbau

- `core/`: Datenmodell und Planer (reines Kotlin, Constraint-Solver [Choco](https://choco-solver.org)). Läuft ohne Android und ist komplett automatisch getestet.
- `app/`: die Android-App (Jetpack Compose).

Tests lokal ausführen: `./gradlew :core:test` (ohne Android-SDK: `./gradlew -PohneApp :core:test`)

Neue Version veröffentlichen: Actions → Release → "Run workflow" mit Versionsnummer (oder Tag `vX.Y.Z` pushen). Testversionen erscheinen als Vorabversion. Jede Version muss höher sein als alle bisherigen.

Updates: Die App fragt beim Start die GitHub-Releases ab (auch Vorabversionen), lädt eine neuere APK und öffnet den Android-Installer. Debug-Builds aus den Actions sind anders signiert und suchen deshalb nicht nach Updates.
