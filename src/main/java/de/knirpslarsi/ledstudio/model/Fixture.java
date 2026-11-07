package de.knirpslarsi.ledstudio.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ein physisches Bauteil im Layout (Lüfter, Strip, Tastatur ...). Verweist auf einen LED-Adressbereich:
 * Gerät + Zone + Start + Anzahl. Mehrere Fixtures dürfen denselben Bereich nutzen (Splitter = doppelte Adressen).
 * Die Reihenfolge in {@link Project#fixtures} ist die Kette, über die Effekte laufen.
 */
public class Fixture {
    public enum Shape {
        RING("Kreis / Lüfter"), DUAL_RING("Doppelring"), SQUARE("Rechteck / abgerundet"), LINE("Linie / Strip"),
        ARC("Bogen"), GRID("Raster / Matrix"), FREE("Frei (einzelne LEDs)");

        public final String label;

        Shape(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public String id = UUID.randomUUID().toString();
    public String name = "Lüfter";
    public String deviceKey = "";
    public String zoneName = "";
    /** Erste LED innerhalb der Zone. */
    public int start;
    public int count = 8;

    public Shape shape = Shape.RING;
    public double x, y;
    public double width = 120, height = 120;
    public double rotation;
    public boolean flipX, flipY;
    /** Winkel der ersten LED; 0 = rechts, 90 = unten. */
    public double startAngle;
    /** LEDs laufen gegen den Uhrzeigersinn bzw. andersherum. */
    public boolean reverse;

    public int innerCount = 8;
    public double corner = 0.25;
    public double arcDegrees = 180;
    public int cols = 8;
    public boolean serpentine = true;
    /** Lokale Positionen (-0.5..0.5) für Shape.FREE. */
    public List<double[]> points = new ArrayList<>();

    /** LED-Nummern ab 1, die dauerhaft aus bleiben. */
    public List<Integer> skip = new ArrayList<>();
    public String colorOrder = "RGB";
    public double brightness = 1.0;

    public Fixture copy() {
        Fixture f = new Fixture();
        f.name = name;
        f.deviceKey = deviceKey;
        f.zoneName = zoneName;
        f.start = start;
        f.count = count;
        f.shape = shape;
        f.x = x;
        f.y = y;
        f.width = width;
        f.height = height;
        f.rotation = rotation;
        f.flipX = flipX;
        f.flipY = flipY;
        f.startAngle = startAngle;
        f.reverse = reverse;
        f.innerCount = innerCount;
        f.corner = corner;
        f.arcDegrees = arcDegrees;
        f.cols = cols;
        f.serpentine = serpentine;
        for (double[] p : points) f.points.add(p.clone());
        f.skip = new ArrayList<>(skip);
        f.colorOrder = colorOrder;
        f.brightness = brightness;
        return f;
    }
}
