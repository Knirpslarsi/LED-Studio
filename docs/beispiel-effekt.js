// Beispiel für einen eigenen Effekt. In den Effekt-Ordner kopieren, dann unter
// Einstellungen "Effekte neu laden". Der Effekt erscheint danach in der Effektauswahl.
//
// led: x, y (Position im Layout, 0..1), u, s (Position im Bauteil, -1..1),
//      angle (Winkel im Bauteil, 0..1), fixture (Bauteil in der Kette),
//      k, m (LED im Bauteil, LEDs im Bauteil), seq, count, rim
// t:   Zeit in Sekunden
// p:   die unten deklarierten Regler
var INFO = {
  title: "Zwei Farben",
  params: [
    { name: "speed", label: "Tempo", def: 1.5, min: 0.1, max: 10, step: 0.1 }
  ]
};

function render(led, t, p) {
  var a = (Math.sin(led.x * 8 - t * p.speed) + 1) / 2;
  var b = (Math.sin(led.y * 8 + t * p.speed) + 1) / 2;
  return [255 * a, 0, 255 * b];
}
