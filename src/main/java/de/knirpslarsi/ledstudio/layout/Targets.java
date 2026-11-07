package de.knirpslarsi.ledstudio.layout;

import de.knirpslarsi.ledstudio.model.Fixture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ziel einer Ebene. Gespeichert wird möglichst grob: Ist ein ganzes Gerät gewählt, steht dort das Gerät, damit
 * später hinzugefügte Lüfter dieses Geräts automatisch mitmachen. Eine leere Liste heißt: alles.
 */
public final class Targets {
    public static final String NONE = "none";

    private Targets() {
    }

    public static String device(String key) {
        return "device:" + key;
    }

    public static String zone(String key, String zone) {
        return "zone:" + key + "#" + zone;
    }

    public static String fixture(String id) {
        return "fixture:" + id;
    }

    /** Die IDs aller Bauteile, die zum Ziel gehören. */
    public static Set<String> resolve(List<String> targets, List<Fixture> fixtures) {
        Set<String> out = new LinkedHashSet<>();
        if (targets == null || targets.isEmpty()) {
            fixtures.forEach(f -> out.add(f.id));
            return out;
        }
        Set<String> set = new HashSet<>(targets);
        for (Fixture f : fixtures) {
            if (set.contains(device(f.deviceKey)) || set.contains(zone(f.deviceKey, f.zoneName)) || set.contains(fixture(f.id))) {
                out.add(f.id);
            }
        }
        return out;
    }

    /** Schaltet Bauteile im Ziel an oder aus und gibt die neue, kürzeste Schreibweise zurück. */
    public static List<String> toggle(List<String> targets, List<Fixture> fixtures, Set<String> ids, boolean on) {
        Set<String> chosen = resolve(targets, fixtures);
        if (on) chosen.addAll(ids);
        else chosen.removeAll(ids);
        return normalize(chosen, fixtures);
    }

    static List<String> normalize(Set<String> chosen, List<Fixture> fixtures) {
        List<String> out = new ArrayList<>();
        if (chosen.isEmpty() && !fixtures.isEmpty()) {
            out.add(NONE);
            return out;
        }
        if (fixtures.stream().allMatch(f -> chosen.contains(f.id))) return out;
        Map<String, List<Fixture>> byDevice = new LinkedHashMap<>();
        for (Fixture f : fixtures) byDevice.computeIfAbsent(f.deviceKey, k -> new ArrayList<>()).add(f);
        byDevice.forEach((key, inDevice) -> {
            if (inDevice.stream().allMatch(f -> chosen.contains(f.id))) {
                out.add(device(key));
                return;
            }
            Map<String, List<Fixture>> byZone = new LinkedHashMap<>();
            for (Fixture f : inDevice) byZone.computeIfAbsent(f.zoneName, k -> new ArrayList<>()).add(f);
            byZone.forEach((zone, inZone) -> {
                if (inZone.stream().allMatch(f -> chosen.contains(f.id))) {
                    out.add(zone(key, zone));
                } else {
                    inZone.stream().filter(f -> chosen.contains(f.id)).forEach(f -> out.add(fixture(f.id)));
                }
            });
        });
        return out;
    }
}
