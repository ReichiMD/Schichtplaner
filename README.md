# Schichtplaner

Android-App, die einer Filialleitung den Wochen-Schichtplan abnimmt. Alles bleibt lokal auf dem Handy.

## Aufbau

- `core/`: Datenmodell und Planer (reines Kotlin, Constraint-Solver [Choco](https://choco-solver.org)). Läuft ohne Android und ist komplett automatisch getestet.
- `app/`: die Android-App (folgt).

Tests lokal ausführen: `./gradlew :core:test`
