# Regel-Spezifikation (Entwurf)

Markierungen: **[fest]** bestätigt · **[Annahme]** vorläufig, zum Testen · **[offen]** noch zu klären.
Alle ladenspezifischen Werte (Öffnungszeiten, Schichtzeiten, Besetzung, Stunden) werden in der App eingestellt und stehen bewusst nicht hier.

## 1. Laden
- Öffnungszeiten pro Wochentag einstellbar, Feiertage mit eigenen Zeiten.
- Planungszeitraum: 2 oder 4 Wochen, wählbar.

## 2. Schichten und Besetzung
- Schichtvorlagen (z. B. Früh 1, Früh 2, Spät) mit Beginn und Ende, Ende auch relativ zum Ladenschluss.
- Besetzung pro Tag: wie viele Personen je Schichtvorlage.
- Schichtzeiten schwanken in der Praxis leicht. Die App braucht frei änderbare Schichtvorlagen, und jede Schicht im Plan muss von Hand anpassbar sein.
- Pause: Schicht über 6 h → 30 min unbezahlt (7 h Anwesenheit = 6,5 h). Bis 6 h keine Pause. (ArbZG §4)

## 3. Mitarbeiter
- Monatsstunden pro Person einstellbar [fest].
- Pro Person: kann Frühschicht ja/nein, kann Spätschicht ja/nein [fest, optional].
- Pro Person: feste Wochentage, an denen sie nie kann, z. B. Schule [fest].
- Aushilfen aus anderen Filialen: eigene Gruppe, nur bei Bedarf einplanen [Annahme].
- Minderjährige / Azubis [offen]. Falls ja: Jugendarbeitsschutzgesetz prüfen (Arbeitsbeginn, Wochenende, Ruhezeit 12 h, Berufsschultage). Ausnahmen für Bäckereien und Verkaufsstellen genau nachlesen.
- Plant sich die Filialleitung selbst mit ein? [offen]

## 4. Abwesenheiten
- Urlaub (U): harte Sperre.
- Krank (K): wird im fertigen Plan nachgetragen, der Plan wird dann neu gerechnet oder von Hand geflickt.
- Frei-Wunsch: weiche Regel, möglichst erfüllen [Annahme].

## 5. Harte Regeln (dürfen nie verletzt werden)
1. Jede Schicht ist besetzt.
2. Niemand hat zwei Schichten am selben Tag.
3. Kein Einsatz an Urlaubstagen oder gesperrten Wochentagen.
4. Nur Schichten, die die Person kann (früh/spät).
5. Mindestens 11 h Ruhezeit zwischen zwei Schichten (ArbZG §5).
6. Höchstens 6 Arbeitstage am Stück [Annahme, prüfen].

## 6. Weiche Regeln (Fairness, gewichtet)
1. Monatsstunden möglichst genau treffen.
2. Wochenenden gleichmäßig verteilen [fest].
3. Frei-Wünsche erfüllen. Wenn nicht alle gehen, reihum benachteiligen statt immer dieselbe Person [Annahme].
4. Möglichst ähnliche Schicht-Mischung pro Person [Annahme].
5. Weitere Fairness-Kriterien [offen]

## 7. Ausgabe
- Wochenansicht wie das Papierblatt: Zeilen = Mitarbeiter, Spalten = Mo–So, Zeit oder Kürzel pro Zelle.
- PDF-Export ein Blatt pro Woche.
