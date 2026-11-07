package de.knirpslarsi.ledstudio.effects;

import java.util.LinkedHashMap;
import java.util.Map;

/** Beschreibt einen Effekt-Regler; die Oberfläche wird daraus automatisch gebaut. */
public final class ParamSpec {
    public enum Type {FLOAT, INT, BOOL, HUE, COLOR, CHOICE}

    public final String name, label;
    public final Type type;
    public final Object def;
    public double min, max = 1, step = 0.01;
    public String help;
    public Map<String, String> choices;

    private ParamSpec(String name, String label, Type type, Object def) {
        this.name = name;
        this.label = label;
        this.type = type;
        this.def = def;
    }

    public static ParamSpec num(String name, String label, double def, double min, double max, double step) {
        ParamSpec p = new ParamSpec(name, label, Type.FLOAT, def);
        p.min = min;
        p.max = max;
        p.step = step;
        return p;
    }

    public static ParamSpec integer(String name, String label, int def, int min, int max) {
        ParamSpec p = new ParamSpec(name, label, Type.INT, (double) def);
        p.min = min;
        p.max = max;
        p.step = 1;
        return p;
    }

    public static ParamSpec bool(String name, String label, boolean def) {
        return new ParamSpec(name, label, Type.BOOL, def);
    }

    public static ParamSpec hue(double def) {
        return new ParamSpec("hue", "Farbton", Type.HUE, def);
    }

    public static ParamSpec color(String name, String label, String def) {
        return new ParamSpec(name, label, Type.COLOR, def);
    }

    /** choices: abwechselnd Wert und Beschriftung. */
    public static ParamSpec choice(String name, String label, String def, String... choices) {
        ParamSpec p = new ParamSpec(name, label, Type.CHOICE, def);
        p.choices = new LinkedHashMap<>();
        for (int i = 0; i + 1 < choices.length; i += 2) p.choices.put(choices[i], choices[i + 1]);
        return p;
    }

    public ParamSpec help(String text) {
        help = text;
        return this;
    }
}
