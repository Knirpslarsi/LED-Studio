package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.util.Colors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Werte einer Vorlage mit den Standardwerten des Effekts dahinter. Tolerant gegen alte oder kaputte Werte. */
public final class Params {
    private final Map<String, Object> values;
    private final Map<String, ParamSpec> specs = new HashMap<>();

    public Params(List<ParamSpec> specs, Map<String, Object> values) {
        this.values = new HashMap<>(values);
        for (ParamSpec s : specs) this.specs.put(s.name, s);
    }

    private Object raw(String name) {
        Object v = values.get(name);
        if (v != null) return v;
        ParamSpec s = specs.get(name);
        return s == null ? null : s.def;
    }

    public double d(String name) {
        Object v = raw(name);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        ParamSpec spec = specs.get(name);
        return spec != null && spec.def instanceof Number n ? n.doubleValue() : 0;
    }

    public int i(String name) {
        return (int) Math.round(d(name));
    }

    public boolean b(String name) {
        Object v = raw(name);
        if (v instanceof Boolean x) return x;
        if (v instanceof Number n) return n.doubleValue() != 0;
        return "1".equals(v) || "true".equals(v);
    }

    public String s(String name) {
        Object v = raw(name);
        return v == null ? "" : v.toString();
    }

    public int color(String name) {
        try {
            return Colors.parseHex(s(name));
        } catch (RuntimeException e) {
            ParamSpec spec = specs.get(name);
            return spec != null ? Colors.parseHex(spec.def.toString()) : 0;
        }
    }

    /** Überschreibt einzelne Werte (globale Farbwahl). */
    public void set(String name, Object value) {
        values.put(name, value);
    }

    public boolean has(String name) {
        return specs.containsKey(name);
    }
}
