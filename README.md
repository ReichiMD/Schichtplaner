# Schichtplaner

Android-App, die einer Filialleitung den Wochen-Schichtplan abnimmt. Alles bleibt lokal auf dem Handy.

## Aufbau

- `core/`: Datenmodell und Planer (reines Kotlin, Constraint-Solver [Choco](https://choco-solver.org)). Läuft ohne Android und ist komplett automatisch getestet.
- `app/`: die Android-App (Jetpack Compose).

Tests lokal ausführen: `./gradlew :core:test` (ohne Android-SDK: `./gradlew -PohneApp :core:test`)

Neue Version veröffentlichen: Tag `vX.Y.Z` pushen, der Release-Workflow baut die signierte APK.
