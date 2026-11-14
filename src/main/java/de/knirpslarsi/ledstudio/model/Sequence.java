package de.knirpslarsi.ledstudio.model;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.Targets;
import de.knirpslarsi.ledstudio.util.Colors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Sequenzen: Aus einer Reihe von LEDs werden Clips, die nacheinander leuchten, jeder mit seinen eigenen LEDs. Die
 * Clips sind danach ganz normale Clips und lassen sich einzeln weiter bearbeiten.
 */
public final class Sequence {
    private Sequence() {
    }

    public static final Map<String, String> ORDERS = new LinkedHashMap<>();

    static {
        ORDERS.put("chain", "Entlang der Kette");
        ORDERS.put("reverse", "Rückwärts");
        ORDERS.put("pingpong", "Hin und zurück");
        ORDERS.put("center", "Aus der Mitte nach außen");
        ORDERS.put("edges", "Von außen zur Mitte");
        ORDERS.put("clicked", "In Klick-Reihenfolge");
        ORDERS.put("random", "Zufällig");
    }

    /** Einstellungen einer Sequenz. */
    public static final class Spec {
        public String order = "chain";
        /** Sekunden pro Schritt. */
        public double step = 0.25;
        /** LEDs pro Schritt. */
        public int group = 1;
        /** Wie viele Schritte eine LED leuchtet (1 = bis zum nächsten Schritt). */
        public double hold = 1;
        public String colorA = "#A020FF", colorB = "#00DDFF";
        public int repeats = 1;
        public double fadeIn = 0.02, fadeOut = 0.1;
    }

    /**
     * Die LEDs eines Ziels als "bauteil#index". In Klick-Reihenfolge, wenn das Ziel aus einzeln gewählten LEDs besteht,
     * sonst entlang der Kette.
     */
    public static List<String> leds(List<String> targets, List<Fixture> fixtures, CompiledLayout layout, boolean clickedOrder) {
        List<String> single = new ArrayList<>();
        for (String t : targets) if (t.startsWith("led:")) single.add(t.substring(4));
        Set<String> whole = Targets.resolve(targets, fixtures);
        if (clickedOrder && !single.isEmpty() && whole.isEmpty()) return single;
        List<CompiledLayout.Led> leds = new ArrayList<>(List.of(layout.leds));
        leds.sort(Comparator.comparingInt(l -> l.seq));
        Set<String> out = new LinkedHashSet<>();
        Set<String> singles = new java.util.HashSet<>(single);
        for (CompiledLayout.Led l : leds) {
            if (l.fixture >= fixtures.size()) continue;
            String id = fixtures.get(l.fixture).id;
            String key = id + "#" + l.k;
            if (whole.contains(id) || singles.contains(key)) out.add(key);
        }
        return new ArrayList<>(out);
    }

    /** Reihenfolge der Schritte: jede Liste ist ein Schritt mit seinen LEDs. */
    static List<List<String>> steps(List<String> leds, Spec spec, Random rnd) {
        List<String> order = new ArrayList<>(leds);
        switch (spec.order) {
            case "reverse" -> Collections.reverse(order);
            case "random" -> Collections.shuffle(order, rnd);
            default -> {
            }
        }
        int group = Math.max(1, spec.group);
        List<List<String>> steps = new ArrayList<>();
        if ("center".equals(spec.order) || "edges".equals(spec.order)) {
            // Paare von der Mitte nach außen (oder umgekehrt), beide Seiten gleichzeitig
            int n = order.size();
            List<List<String>> rings = new ArrayList<>();
            for (int i = 0; i < (n + 1) / 2; i++) {
                int left = (n - 1) / 2 - i, right = n / 2 + i;
                List<String> ring = new ArrayList<>();
                if (left >= 0) ring.add(order.get(left));
                if (right < n && right != left) ring.add(order.get(right));
                rings.add(ring);
            }
            if ("edges".equals(spec.order)) Collections.reverse(rings);
            for (int i = 0; i < rings.size(); i += group) {
                List<String> step = new ArrayList<>();
                for (int k = i; k < Math.min(rings.size(), i + group); k++) step.addAll(rings.get(k));
                steps.add(step);
            }
            return steps;
        }
        for (int i = 0; i < order.size(); i += group) steps.add(new ArrayList<>(order.subList(i, Math.min(order.size(), i + group))));
        if ("pingpong".equals(spec.order) && steps.size() > 2) {
            for (int i = steps.size() - 2; i > 0; i--) steps.add(steps.get(i));
        }
        return steps;
    }

    /** Erzeugt die Clips der Sequenz ab start. */
    public static List<Show.Clip> build(List<String> leds, double start, Spec spec, Random rnd) {
        List<Show.Clip> out = new ArrayList<>();
        if (leds.isEmpty()) return out;
        int a = Colors.parseHex(spec.colorA), b = Colors.parseHex(spec.colorB);
        double step = Math.max(0.01, spec.step), hold = Math.max(0.1, spec.hold);
        double t = start;
        for (int r = 0; r < Math.max(1, spec.repeats); r++) {
            List<List<String>> steps = steps(leds, spec, rnd);
            for (int i = 0; i < steps.size(); i++) {
                Show.Clip c = new Show.Clip();
                c.start = t;
                c.end = t + step * hold;
                c.fadeIn = Math.min(spec.fadeIn, (c.end - c.start) / 2);
                c.fadeOut = Math.min(spec.fadeOut, (c.end - c.start) / 2);
                double f = steps.size() <= 1 ? 0 : i / (double) (steps.size() - 1);
                c.look = Project.Layer.solid(Colors.toHex(Colors.lerp(a, b, f)));
                for (String led : steps.get(i)) c.targets.add("led:" + led);
                out.add(c);
                t += step;
            }
        }
        return out;
    }
}
