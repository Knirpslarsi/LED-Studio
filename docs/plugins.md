# Plugins für LED Studio

Ein Plugin bringt neue **Effekte** und optional **Vorlagen** mit. Es liegt in einem öffentlichen GitHub-Repo; jeder kann
es in LED Studio unter **Plugins → Plugin installieren** mit `name/repo` oder dem GitHub-Link installieren.

Effekte aus Plugins laufen abgeschottet: Sie berechnen nur Farben und haben keinen Zugriff auf Dateien, das Internet
oder den PC. Rechnet ein Effekt zu lange (z. B. eine Endlosschleife), hält LED Studio ihn an.

## Schnellstart

1. In LED Studio **Plugins → Eigenes Plugin erstellen**: Name eingeben, **Erstellen**.
   Der Ordner öffnet sich; das Plugin ist sofort aktiv und bringt den Effekt „Beispiel: Farbwelle“ mit.
2. Effekt in `effects/beispiel.js` ändern oder neue `.js`-Dateien anlegen und in `plugin.json` eintragen.
3. In LED Studio **Neu laden** – die Effekte stehen in der Effektliste, im Feinschliff und bei den Spiel-Animationen.
4. Optional: **Vorlagen ins Plugin …** schreibt ausgewählte Vorlagen in `presets.json`.
5. Ordnerinhalt in ein neues, **öffentliches** GitHub-Repo hochladen (`.source.json` ist per `.gitignore` ausgenommen).

## Aufbau

```
mein-plugin/
├── plugin.json
├── effects/
│   └── beispiel.js
├── presets.json      (optional)
└── README.md
```

### plugin.json

```json
{
  "id": "meine-effekte",
  "name": "Meine Effekte",
  "version": "1.0.0",
  "author": "Dein Name",
  "description": "Neon-Wellen und mehr.",
  "minAppVersion": "1.6.0",
  "effects": ["effects/beispiel.js"],
  "presets": "presets.json"
}
```

| Feld | Bedeutung |
|---|---|
| `id` | Eindeutig, Kleinbuchstaben, Zahlen und `-` (2–40 Zeichen). Nicht mehr ändern, sonst finden Vorlagen die Effekte nicht. |
| `version` | Für Updates; bei jeder neuen Version erhöhen. |
| `minAppVersion` | Optional: älteste LED-Studio-Version, die das Plugin kann. |
| `effects` | Effekt-Dateien relativ zum Plugin-Ordner. |
| `presets` | Optional: Datei mit Vorlagen. |

Die Effekte bekommen die ID `plugin_<id>_<dateiname>`, z. B. `plugin_meine-effekte_beispiel`. So können Vorlagen in
`presets.json` sie verwenden.

### Effekte (JavaScript)

```js
var INFO = {
  title: "Neon-Welle",
  params: [
    {name: "speed", label: "Tempo", def: 0.5, min: 0, max: 3, step: 0.05},  // Zahl → Regler
    {name: "color", label: "Farbe", def: "#00FF80"},                         // Text → Farbwähler
    {name: "glow",  label: "Weiche Kanten", def: true}                       // true/false → Schalter
  ]
};

function render(led, t, p) {
  var v = 0.5 + 0.5 * Math.sin(2 * Math.PI * (led.x - t * p.speed));
  return [0, 255 * v, 128 * v];   // [rot, grün, blau], je 0..255
}
```

`render` läuft in jedem Bild für jede LED.

| `led.` | Bedeutung |
|---|---|
| `x`, `y` | Lage im Layout, 0..1 (längere Seite) |
| `angle` | Winkel im Lüfter, 0..1 |
| `u`, `s` | Lage im Bauteil, −1..1 |
| `fixture` | Nummer des Bauteils |
| `k`, `m` | Nummer der LED im Bauteil und LED-Zahl des Bauteils |
| `seq`, `count` | Nummer der LED in der Kette und LEDs gesamt |
| `rim` | Ober-/Unterkante eines Rings |

`t` ist die Zeit in Sekunden, `p` enthält die Einstellungen aus `INFO.params`.

## Neue Versionen

1. `version` in `plugin.json` erhöhen und hochladen.
2. Auf GitHub ein **Release** mit passendem Tag anlegen, z. B. `v1.0.1`.

LED Studio installiert immer das neueste Release, ohne Release den Hauptzweig. Unter **Plugins → Nach Updates suchen**
bekommen alle Nutzer die neue Version. Ein- und Ausschalten sowie mitgebrachte Vorlagen bleiben beim Update erhalten.

## In den Katalog

Der Katalog unter **Plugins entdecken** kommt aus [`plugins.json`](https://github.com/Knirpslarsi/LED-Studio-Plugins)
im Repo `Knirpslarsi/LED-Studio-Plugins`. Eigenes Plugin per Pull Request eintragen:

```json
{"repo": "dein-name/mein-plugin", "name": "Meine Effekte", "author": "Dein Name", "description": "Kurz, was es kann."}
```

## Erlaubte Dateien

Beim Installieren werden nur `.json`, `.js`, `.md`, `.txt`, `.png` und `.jpg` übernommen, höchstens 500 Dateien und
20 MB. Pfade außerhalb des Plugin-Ordners werden abgewiesen.
