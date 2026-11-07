package de.knirpslarsi.ledstudio.layout;

import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.openrgb.Controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bauteile aus Zonen erzeugen und das Layout auf Probleme prüfen. */
public final class LayoutTools {
    private LayoutTools() {
    }

    /** Sinnvolle Vorgabe, wie viele LEDs pro Bauteil eine Zone hat. */
    public static int suggestLedsPerFixture(Controller c, Controller.Zone z) {
        int n = z.ledCount();
        boolean fanLike = c.type() == 3 || c.name().toLowerCase(Locale.ROOT).contains("nzxt")
                || z.name().toLowerCase(Locale.ROOT).matches(".*(fan|lüfter|channel|argb|addressable).*");
        if (fanLike) {
            for (int perFan : new int[]{8, 12, 16, 18, 20}) if (n >= perFan && n % perFan == 0) return perFan;
        }
        return n;
    }

    /**
     * Findet das Gerät eines Bauteils. Der Anschluss kann sich ändern (anderer USB-Port), deshalb zählt
     * ersatzweise derselbe Name mit derselben Seriennummer, solange das eindeutig ist.
     */
    public static Controller findController(String key, List<Controller> controllers) {
        for (Controller c : controllers) if (c.key().equals(key)) return c;
        String[] parts = key.split("[|]", -1);
        if (parts.length < 3) return null;
        Controller match = null;
        for (Controller c : controllers) {
            if (c.name().equals(parts[0]) && c.serial().equals(parts[parts.length - 1])) {
                if (match != null) return null;
                match = c;
            }
        }
        return match;
    }

    /** Teilt eine Zone an den Segmenten, die in OpenRGB schon eingetragen sind (z. B. VRM und FAN). */
    public static List<Fixture> fromSegments(Controller c, Controller.Zone z, double x, double y) {
        List<Fixture> out = new ArrayList<>();
        double cursor = x;
        for (Controller.Segment seg : z.segments()) {
            if (seg.count() <= 0) continue;
            Fixture f = new Fixture();
            f.deviceKey = c.key();
            f.zoneName = z.name();
            f.start = seg.start();
            f.count = seg.count();
            f.name = seg.name();
            f.shape = Fixture.Shape.LINE;
            f.width = Math.max(60, f.count * 16);
            f.height = 20;
            f.x = cursor + f.width / 2 - 60;
            f.y = y;
            cursor += f.width + 40;
            out.add(f);
        }
        return out;
    }

    /** Bauteile für eine Zone: eine Matrix wird zur echten Geräteform, sonst Lüfterreihe oder Strip. */
    public static List<Fixture> fromZone(Controller c, Controller.Zone z, int perFixture, double x, double y) {
        List<Fixture> out = new ArrayList<>();
        if (z.ledCount() <= 0) return out;
        if (z.isMatrix()) {
            out.add(matrixFixture(c, z, x, y));
            return out;
        }
        perFixture = Math.max(1, Math.min(perFixture, z.ledCount()));
        boolean fans = perFixture < z.ledCount() || perFixture == 8 || c.type() == 3;
        int number = 1;
        double spacing = 140;
        for (int start = 0; start < z.ledCount(); start += perFixture) {
            Fixture f = new Fixture();
            f.deviceKey = c.key();
            f.zoneName = z.name();
            f.start = start;
            f.count = Math.min(perFixture, z.ledCount() - start);
            if (fans) {
                f.shape = Fixture.Shape.RING;
                f.width = f.height = 120;
                f.x = x + (number - 1) * spacing;
                f.y = y;
                f.name = z.name() + " · Lüfter " + number;
            } else {
                f.shape = Fixture.Shape.LINE;
                f.width = Math.max(60, f.count * 16);
                f.height = 20;
                f.x = x + f.width / 2 - 60;
                f.y = y;
                f.name = z.name();
            }
            out.add(f);
            number++;
        }
        return out;
    }

    private static Fixture matrixFixture(Controller c, Controller.Zone z, double x, double y) {
        Fixture f = new Fixture();
        f.deviceKey = c.key();
        f.zoneName = z.name();
        f.name = c.displayName() + " · " + z.name();
        f.count = z.ledCount();
        f.shape = Fixture.Shape.FREE;
        double cell = 19;
        f.width = Math.max(1, z.matrixWidth() - 1) * cell;
        f.height = Math.max(1, z.matrixHeight() - 1) * cell;
        double[][] points = new double[f.count][];
        for (int row = 0; row < z.matrixHeight(); row++) {
            for (int col = 0; col < z.matrixWidth(); col++) {
                int led = z.matrix()[row * z.matrixWidth() + col];
                if (led >= 0 && led < f.count && points[led] == null) {
                    points[led] = new double[]{
                            z.matrixWidth() == 1 ? 0 : (double) col / (z.matrixWidth() - 1) - 0.5,
                            z.matrixHeight() == 1 ? 0 : (double) row / (z.matrixHeight() - 1) - 0.5};
                }
            }
        }
        for (int i = 0; i < points.length; i++) {
            // LEDs ohne Platz in der Matrix (z.B. Seitenleisten) unten anreihen, statt sie zu verlieren.
            f.points.add(points[i] != null ? points[i]
                    : new double[]{-0.5 + (i % 20) * 0.05, 0.6 + (i / 20) * 0.1});
        }
        f.x = x + f.width / 2 - 60;
        f.y = y + f.height / 2 - 60;
        return f;
    }

    /** Alle Zonen untereinander anordnen. */
    public static List<Fixture> autoLayout(List<Controller> controllers) {
        List<Fixture> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        double y = 0;
        for (Controller c : controllers) {
            if (!seen.add(c.key())) continue;
            for (Controller.Zone z : c.zones()) {
                List<Fixture> row = z.segments().size() > 1 ? fromSegments(c, z, 0, y)
                        : fromZone(c, z, suggestLedsPerFixture(c, z), 0, y);
                if (row.isEmpty()) continue;
                out.addAll(row);
                double bottom = row.stream().mapToDouble(f -> f.y + f.height / 2).max().orElse(y);
                y = bottom + 110;
            }
        }
        return out;
    }

    /** Sortiert die Kette nach Position: von links nach rechts, dann von oben nach unten. */
    public static void sortChainByPosition(List<Fixture> fixtures) {
        fixtures.sort(Comparator.<Fixture>comparingDouble(f -> Math.round(f.x / 20) * 20.0).thenComparingDouble(f -> f.y));
    }

    public record Issue(Kind kind, String title, String detail, List<String> fixtureIds) {
        public enum Kind {DUPLICATE_DEVICE, SHARED_ADDRESS, OUT_OF_RANGE, MISSING_DEVICE, UNMAPPED}
    }

    /** Findet doppelt erkannte Geräte, mehrfach belegte Adressen und Bauteile außerhalb ihrer Zone. */
    public static List<Issue> analyze(List<Fixture> fixtures, List<Controller> controllers) {
        List<Issue> issues = new ArrayList<>();
        issues.addAll(duplicateDevices(controllers));
        Map<String, List<Fixture>> perAddress = new LinkedHashMap<>();
        for (Fixture f : fixtures) {
            Controller c = findController(f.deviceKey, controllers);
            Controller.Zone z = c == null ? null : c.zone(f.zoneName);
            if (z == null) {
                issues.add(new Issue(Issue.Kind.MISSING_DEVICE, "Gerät fehlt: " + f.name,
                        "Das Bauteil verweist auf eine Zone, die OpenRGB gerade nicht meldet.", List.of(f.id)));
                continue;
            }
            if (f.start + f.count > z.ledCount()) {
                issues.add(new Issue(Issue.Kind.OUT_OF_RANGE, "Zu viele LEDs: " + f.name,
                        "Braucht bis LED " + (f.start + f.count) + ", die Zone hat aber nur " + z.ledCount() + ".", List.of(f.id)));
            }
            for (int k = 0; k < f.count && f.start + k < z.ledCount(); k++) {
                perAddress.computeIfAbsent(c.key() + "#" + (z.start() + f.start + k), a -> new ArrayList<>()).add(f);
            }
        }
        Map<String, Integer> pairs = new LinkedHashMap<>();
        Map<String, List<String>> pairIds = new HashMap<>();
        for (List<Fixture> users : perAddress.values()) {
            for (int i = 0; i < users.size(); i++) {
                for (int j = i + 1; j < users.size(); j++) {
                    if (users.get(i) == users.get(j)) continue;
                    String label = users.get(i).name + " ↔ " + users.get(j).name;
                    pairs.merge(label, 1, Integer::sum);
                    pairIds.put(label, List.of(users.get(i).id, users.get(j).id));
                }
            }
        }
        pairs.forEach((label, count) -> issues.add(new Issue(Issue.Kind.SHARED_ADDRESS, "Doppelte Adressen: " + label,
                count + " LED-Adressen sind doppelt belegt. Beide Bauteile zeigen immer dieselbe Farbe (z. B. ARGB-Splitter).",
                pairIds.get(label))));

        for (Controller c : controllers) {
            int placed = 0;
            for (Controller.Zone z : c.zones()) {
                for (int i = 0; i < z.ledCount(); i++) if (perAddress.containsKey(c.key() + "#" + (z.start() + i))) placed++;
            }
            if (placed > 0 && placed < c.ledCount()) {
                issues.add(new Issue(Issue.Kind.UNMAPPED, "Nicht zugeordnet: " + c.displayName(),
                        (c.ledCount() - placed) + " von " + c.ledCount() + " LEDs sind nicht im Layout und bleiben aus, solange ein Effekt läuft.",
                        List.of()));
            }
        }
        return issues;
    }

    /** Gleiche Seriennummer oder gleicher Anschluss heißt: dasselbe Gerät wurde zweimal erkannt. */
    public static List<Issue> duplicateDevices(List<Controller> controllers) {
        List<Issue> issues = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < controllers.size(); i++) {
            for (int j = i + 1; j < controllers.size(); j++) {
                Controller a = controllers.get(i), b = controllers.get(j);
                String reason = null;
                if (!a.serial().isBlank() && a.serial().equals(b.serial()) && a.name().equals(b.name())) reason = "gleiche Seriennummer (" + a.serial() + ")";
                else if (!a.location().isBlank() && a.location().equals(b.location()) && a.name().equals(b.name()))
                    reason = "gleicher Anschluss";
                // RAM-Riegel heißen alle gleich und sitzen an verschiedenen Adressen: das ist kein Duplikat.
                else if (a.type() != 1 && !a.name().isBlank() && a.name().equals(b.name()) && a.ledCount() == b.ledCount() && a.ledCount() > 0)
                    reason = "gleicher Name und gleich viele LEDs; das können aber auch zwei echte Geräte sein";
                if (reason != null && reported.add(i + ":" + j)) {
                    issues.add(new Issue(Issue.Kind.DUPLICATE_DEVICE, "Gerät doppelt erkannt? " + a.displayName(),
                            "Geräte " + (i + 1) + " und " + (j + 1) + ": " + reason + ".", List.of()));
                }
            }
        }
        return issues;
    }

    /** Stellt das Verkabelungs-Modell der alten Software her: NZXT Channel 5 (gespiegelt) → 1 → 2, 8 LEDs je Lüfter. */
    public static List<Fixture> legacyNzxtChain(List<Controller> controllers) {
        List<Fixture> out = new ArrayList<>();
        Controller nzxt = controllers.stream()
                .filter(c -> c.name().toLowerCase(Locale.ROOT).contains("nzxt")).findFirst().orElse(null);
        if (nzxt == null) return out;
        int number = 0;
        for (String channel : new String[]{"5", "1", "2"}) {
            for (Controller.Zone z : nzxt.zones()) {
                if (!z.name().toLowerCase(Locale.ROOT).endsWith("channel " + channel)) continue;
                for (int start = 0; start + 8 <= z.ledCount(); start += 8) {
                    Fixture f = new Fixture();
                    f.deviceKey = nzxt.key();
                    f.zoneName = z.name();
                    f.start = start;
                    f.count = 8;
                    f.name = "Channel " + channel + " · Lüfter " + (start / 8 + 1);
                    f.flipX = channel.equals("5");
                    f.width = f.height = channel.equals("5") ? 140 : 120;
                    f.x = number * 170;
                    out.add(f);
                    number++;
                }
            }
        }
        return out;
    }
}
