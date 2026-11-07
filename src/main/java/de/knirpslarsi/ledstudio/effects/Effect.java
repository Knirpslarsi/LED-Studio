package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;

import java.util.List;

/**
 * Ein Effekt berechnet pro Bild eine Farbe je LED-Position. Über {@code layout} kennt er die echte Lage jeder LED,
 * ihr Bauteil und die Kette. Effekte mit Zustand setzen ihn in {@link #reset} zurück.
 */
public abstract class Effect {
    /** Daten eines Bildes. */
    public static final class Frame {
        public CompiledLayout layout;
        /** Zeit mit globalem Tempo verrechnet. */
        public double t;
        /** Echte Zeit in Sekunden. */
        public double rawT;
        /** Sekunden seit dem letzten Bild, mit globalem Tempo. */
        public double dt;
        public Params p;
        public int[] out;
    }

    public abstract String id();

    public abstract String title();

    /** Kurzbeschreibung für die Oberfläche. */
    public String blurb() {
        return "";
    }

    public abstract List<ParamSpec> params();

    public void reset(CompiledLayout layout) {
    }

    public abstract void render(Frame f);
}
