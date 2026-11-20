// Beispiel-Effekt: eine Farbwelle, die über alle LEDs wandert.
//
// render(led, t, p) wird in jedem Bild für jede LED aufgerufen und gibt [rot, grün, blau] zurück (je 0 bis 255).
//   led: x, y (Lage, 0..1), angle (Winkel im Lüfter, 0..1), fixture (Nummer des Bauteils), k (LED im Bauteil),
//        m (LEDs im Bauteil), seq (LED in der Kette), count (LEDs gesamt), rim (Ober-/Unterkante eines Rings)
//   t:   Zeit in Sekunden
//   p:   deine Einstellungen aus INFO.params (Zahl, Farbe als "#RRGGBB" oder an/aus)
//
// Effekte laufen abgeschottet: kein Zugriff auf Dateien, Internet oder den PC.

var INFO = {
  title: "Beispiel: Farbwelle",
  params: [
    {name: "speed", label: "Tempo", def: 0.5, min: 0, max: 3, step: 0.05},
    {name: "color", label: "Farbe", def: "#A020FF"},
    {name: "width", label: "Wellenlänge", def: 1, min: 0.2, max: 4, step: 0.1},
    {name: "glow", label: "Weiche Kanten", def: true}
  ]
};

function hex(c, i) {
  return parseInt(c.substr(1 + 2 * i, 2), 16);
}

function render(led, t, p) {
  var wave = 0.5 + 0.5 * Math.sin(2 * Math.PI * (led.x / p.width - t * p.speed));
  if (!p.glow) wave = wave > 0.5 ? 1 : 0;
  return [hex(p.color, 0) * wave, hex(p.color, 1) * wave, hex(p.color, 2) * wave];
}
