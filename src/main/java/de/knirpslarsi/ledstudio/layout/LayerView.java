package de.knirpslarsi.ledstudio.layout;

import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.model.Project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Der Ausschnitt des Layouts, den der Effekt einer Ebene sieht. Für den Effekt ist das sein ganzer Aufbau. */
public final class LayerView {
    public final CompiledLayout layout;
    /** Position in {@code layout.leds} → Index im gesamten Layout. */
    public final int[] toGlobal;

    private LayerView(CompiledLayout layout, int[] toGlobal) {
        this.layout = layout;
        this.toGlobal = toGlobal;
    }

    public static LayerView build(CompiledLayout parent, List<Fixture> fixtures, Project.Layer layer) {
        Set<String> ids = Targets.resolve(layer.targets, fixtures);
        Set<String> single = Targets.leds(layer.targets);
        List<Led> chosen = new ArrayList<>();
        List<Integer> global = new ArrayList<>();
        Map<Integer, Integer> chainIndex = new HashMap<>();
        for (int i = 0; i < parent.leds.length; i++) {
            Led l = parent.leds[i];
            if (l.fixture >= fixtures.size() || !passes(layer.ledFilter, l)) continue;
            String fid = fixtures.get(l.fixture).id;
            if (!ids.contains(fid) && !single.contains(fid + "#" + l.k)) continue;
            Led c = l.copy();
            c.fixture = chainIndex.computeIfAbsent(l.fixture, f -> chainIndex.size());
            chosen.add(c);
            global.add(i);
        }
        int fans = chainIndex.size();
        if (layer.reverse) {
            java.util.Collections.reverse(chosen);
            java.util.Collections.reverse(global);
            for (Led l : chosen) {
                l.fixture = fans - 1 - l.fixture;
                l.u = -l.u;
                l.angle = l.angle == 0 ? 0 : 1 - l.angle;
            }
        }
        for (int i = 0; i < chosen.size(); i++) chosen.get(i).seq = i;

        // Globaler Bereich: Effekt läuft über den ganzen Aufbau. Sonst füllt er genau sein Ziel.
        boolean own = !"global".equals(layer.scope);
        double[] xs = new double[chosen.size()], ys = new double[chosen.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = own ? chosen.get(i).wx : chosen.get(i).x;
            ys[i] = own ? chosen.get(i).wy : chosen.get(i).y;
        }
        double w = own ? 1 : parent.width, h = own ? 1 : parent.height;
        if (own) {
            double[] box = normalize(xs, ys);
            w = box[0];
            h = box[1];
        }
        if (layer.rotate != 0) {
            double a = Math.toRadians(layer.rotate), c = Math.cos(a), s = Math.sin(a);
            for (int i = 0; i < xs.length; i++) {
                double x = xs[i] - w / 2, y = ys[i] - h / 2;
                xs[i] = x * c - y * s;
                ys[i] = x * s + y * c;
            }
            double[] box = normalize(xs, ys);
            w = box[0];
            h = box[1];
        }
        for (int i = 0; i < xs.length; i++) {
            if (layer.mirrorX) xs[i] = w - xs[i];
            if (layer.mirrorY) ys[i] = h - ys[i];
            chosen.get(i).x = xs[i] * layer.scale;
            chosen.get(i).y = ys[i] * layer.scale;
        }
        int[] map = new int[global.size()];
        for (int i = 0; i < map.length; i++) map[i] = global.get(i);
        return new LayerView(CompiledLayout.view(chosen.toArray(new Led[0]), fans, w * layer.scale, h * layer.scale), map);
    }

    private static boolean passes(String filter, Led l) {
        return switch (filter == null ? "all" : filter) {
            case "rim" -> l.rim;
            case "sides" -> !l.rim;
            case "odd" -> l.k % 2 == 0;
            case "even" -> l.k % 2 == 1;
            default -> true;
        };
    }

    /** Verschiebt nach 0.. und skaliert die längere Seite auf 1. Gibt Breite und Höhe zurück. */
    private static double[] normalize(double[] xs, double[] ys) {
        if (xs.length == 0) return new double[]{1, 1};
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        double span = Math.max(1e-6, Math.max(maxX - minX, maxY - minY));
        for (int i = 0; i < xs.length; i++) {
            xs[i] = (xs[i] - minX) / span;
            ys[i] = (ys[i] - minY) / span;
        }
        return new double[]{(maxX - minX) / span, (maxY - minY) / span};
    }
}
