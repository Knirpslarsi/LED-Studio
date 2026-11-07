package de.knirpslarsi.ledstudio.layout;

import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.openrgb.Controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unveränderlicher, für Effekte aufbereiteter Stand des Layouts. */
public final class CompiledLayout {
    /** Eine LED an einer physischen Position. Eine Adresse kann mehrere Positionen haben (doppelte Adressen). */
    public static final class Led {
        /** Reihenfolge entlang der Kette. */
        public int seq;
        public int fixture;
        /** Index der LED im Bauteil und LED-Zahl des Bauteils. */
        public int k, m;
        /** Weltposition (mm). */
        public double wx, wy;
        /** Normalisiert: längere Layoutseite = 0..1, Seitenverhältnis bleibt erhalten. */
        public double x, y;
        /** Position im Bauteil: -1 links .. +1 rechts bzw. -1 oben .. +1 unten. */
        public double u, s;
        /** Polarwinkel im Bauteil (0..1) und Abstand zur Mitte. */
        public double angle, radius;
        /** Ober-/Unterkante eines Rings (nicht die seitlichen LEDs). */
        public boolean rim;
        public boolean skipped;
        public double brightness = 1;
        /** Index in {@link #devices}, -1 wenn das Gerät gerade fehlt. */
        public int device = -1;
        /** LED-Index im Gerät. */
        public int address = -1;
    }

    /** Ein Gerät, das mindestens eine LED im Layout hat. */
    public static final class DeviceSlot {
        public final int controllerIndex;
        public final String name;
        public final int ledCount;
        public final String[] colorOrder;

        DeviceSlot(Controller c) {
            controllerIndex = c.index();
            name = c.displayName();
            ledCount = c.ledCount();
            colorOrder = new String[ledCount];
        }
    }

    public final Led[] leds;
    public final List<DeviceSlot> devices;
    public final int fanCount;
    /** Layoutgröße normalisiert (max(width, height) == 1). */
    public final double width, height;
    /** Fixture-Indizes, deren Gerät oder Zone gerade nicht gefunden wird. */
    public final List<Integer> unresolved;
    /** Pro Fixture: Index der ersten LED in {@link #leds}. */
    public final int[] fixtureStart;

    private CompiledLayout(Led[] leds, List<DeviceSlot> devices, int fanCount, double width, double height,
                           List<Integer> unresolved, int[] fixtureStart) {
        this.leds = leds;
        this.devices = devices;
        this.fanCount = fanCount;
        this.width = width;
        this.height = height;
        this.unresolved = unresolved;
        this.fixtureStart = fixtureStart;
    }

    public static CompiledLayout compile(List<Fixture> fixtures, List<Controller> controllers) {
        Map<Integer, Integer> slotOf = new HashMap<>();
        List<DeviceSlot> slots = new ArrayList<>();
        List<Led> out = new ArrayList<>();
        List<Integer> unresolved = new ArrayList<>();
        int[] starts = new int[fixtures.size()];
        double[] w = new double[2];
        for (int fi = 0; fi < fixtures.size(); fi++) {
            Fixture f = fixtures.get(fi);
            starts[fi] = out.size();
            Controller c = LayoutTools.findController(f.deviceKey, controllers);
            Controller.Zone zone = c == null ? null : c.zone(f.zoneName);
            if (zone == null) unresolved.add(fi);
            int slot = -1;
            if (zone != null) {
                slot = slotOf.computeIfAbsent(c.index(), idx -> {
                    slots.add(new DeviceSlot(c));
                    return slots.size() - 1;
                });
            }
            double[][] local = Shapes.local(f);
            boolean ringLike = f.shape == Fixture.Shape.RING || f.shape == Fixture.Shape.DUAL_RING || f.shape == Fixture.Shape.SQUARE;
            for (int k = 0; k < local.length; k++) {
                Led led = new Led();
                led.seq = out.size();
                led.fixture = fi;
                led.k = k;
                led.m = local.length;
                Shapes.toWorld(f, local[k][0], local[k][1], w);
                led.wx = w[0];
                led.wy = w[1];
                double du = (w[0] - f.x) / Math.max(1e-6, f.width / 2);
                double ds = (w[1] - f.y) / Math.max(1e-6, f.height / 2);
                led.radius = Math.hypot(local[k][0], local[k][1]) * 2;
                led.angle = local[k][0] == 0 && local[k][1] == 0 ? 0
                        : (Math.atan2(local[k][1], local[k][0]) / (2 * Math.PI) + 1) % 1;
                if (ringLike) {
                    led.u = du;
                    led.s = ds;
                    led.rim = Math.abs(ds) >= 0.5 - 1e-9;
                } else {
                    // Lineare Formen laufen entlang ihrer eigenen Achse, egal wie sie gedreht sind.
                    double along = f.shape == Fixture.Shape.LINE || local.length < 2
                            ? (local.length < 2 ? 0 : 2.0 * k / (local.length - 1) - 1) : du;
                    led.u = along;
                    led.s = ds;
                    led.rim = true;
                }
                led.skipped = f.skip.contains(k + 1);
                led.brightness = f.brightness;
                if (zone != null && k + f.start < zone.ledCount() && slot >= 0) {
                    led.device = slot;
                    led.address = zone.start() + f.start + k;
                    DeviceSlot d = slots.get(slot);
                    if (led.address < d.ledCount && d.colorOrder[led.address] == null) d.colorOrder[led.address] = f.colorOrder;
                }
                out.add(led);
            }
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Led l : out) {
            minX = Math.min(minX, l.wx);
            maxX = Math.max(maxX, l.wx);
            minY = Math.min(minY, l.wy);
            maxY = Math.max(maxY, l.wy);
        }
        double span = Math.max(1e-6, Math.max(maxX - minX, maxY - minY));
        for (Led l : out) {
            l.x = (l.wx - minX) / span;
            l.y = (l.wy - minY) / span;
        }
        double width = out.isEmpty() ? 1 : (maxX - minX) / span, height = out.isEmpty() ? 1 : (maxY - minY) / span;
        return new CompiledLayout(out.toArray(new Led[0]), List.copyOf(slots), fixtures.size(), width, height,
                List.copyOf(unresolved), starts);
    }
}
