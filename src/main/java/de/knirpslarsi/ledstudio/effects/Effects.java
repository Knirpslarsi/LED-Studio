package de.knirpslarsi.ledstudio.effects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Verzeichnis aller Effekte. */
public final class Effects {
    private static final Map<String, Supplier<Effect>> BUILTIN = new LinkedHashMap<>();
    private static volatile Map<String, Supplier<Effect>> custom = Map.of();

    static {
        add(LegacyEffects.EdgeSweep::new);
        add(LegacyEffects.RimChase::new);
        SpatialEffects.all().forEach(Effects::add);
        add(LegacyEffects.Rainbow::new);
        add(LegacyEffects.Breathing::new);
        add(LegacyEffects.Comet::new);
        add(LegacyEffects.Wave::new);
        add(LegacyEffects.Fire::new);
        add(LegacyEffects.Static::new);
        add(LegacyEffects.CounterWave::new);
        add(MusicEffect::new);
        add(MusicEffect.PulseRings::new);
    }

    private Effects() {
    }

    private static void add(Supplier<Effect> s) {
        BUILTIN.put(s.get().id(), s);
    }

    public static void setCustom(Map<String, Supplier<Effect>> effects) {
        custom = Map.copyOf(effects);
    }

    public static Effect create(String id) {
        Supplier<Effect> s = BUILTIN.get(id);
        if (s == null) s = custom.get(id);
        return s == null ? null : s.get();
    }

    public static List<Effect> catalog() {
        List<Effect> out = new ArrayList<>();
        BUILTIN.values().forEach(s -> out.add(s.get()));
        custom.values().forEach(s -> out.add(s.get()));
        return out;
    }
}
