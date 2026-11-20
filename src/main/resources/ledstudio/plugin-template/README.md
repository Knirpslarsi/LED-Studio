# {{name}}

Ein Plugin für [LED Studio](https://github.com/Knirpslarsi/LED-Studio).

## Installieren

In LED Studio unter **Plugins → Plugin installieren** dieses Repo eintragen, z. B. `dein-name/{{id}}`, und auf
**Installieren** klicken.

## Aufbau

| Datei | Inhalt |
|---|---|
| `plugin.json` | Name, ID, Version, Autor, Beschreibung und die Liste der Effekte |
| `effects/*.js` | Effekte in JavaScript: `INFO` mit Titel und Einstellungen, `render(led, t, p)` gibt `[r, g, b]` zurück |
| `presets.json` | optional: Vorlagen, die beim Installieren dazukommen (in LED Studio mit „Vorlagen ins Plugin“ erzeugen) |

## Veröffentlichen

1. Neues öffentliches Repo auf GitHub anlegen und den Inhalt dieses Ordners hochladen (ohne `.source.json`).
2. Für eine neue Version `version` in `plugin.json` erhöhen und auf GitHub ein Release mit dem Tag `v1.0.1` usw.
   anlegen. LED Studio installiert immer das neueste Release, ohne Release den Hauptzweig.
3. Wer das Plugin installiert hat, bekommt neue Versionen unter **Plugins → Nach Updates suchen**.

Damit es im Katalog in LED Studio auftaucht, das Repo in `plugins.json` von
[LED-Studio-Plugins](https://github.com/Knirpslarsi/LED-Studio-Plugins) eintragen (Pull Request).
