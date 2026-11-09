package de.knirpslarsi.ledstudio.audio;

import java.util.LinkedHashMap;
import java.util.Map;

/** Worauf ein Musik-Effekt reagiert: ein Frequenzbereich, die Gesamtlautstärke oder erkannte Klänge. */
public enum SoundSource {
    BASS("bass", "Bass / Kick-Bereich (40–150 Hz)", 40, 150),
    LOW("low", "Tiefe Töne (40–400 Hz)", 40, 400),
    VOICE("voice", "Stimmen / Gesang (300–3400 Hz)", 300, 3400),
    MID("mid", "Mitten (250–2000 Hz)", 250, 2000),
    HIGH("high", "Hohe Töne (2–16 kHz)", 2000, 16000),
    CYMBAL("cymbal", "Becken / Zischlaute (6–16 kHz)", 6000, 16000),
    ALL("all", "Gesamtlautstärke", 40, 16000),
    KICK("kick", "Kick-Schläge (erkannt)", 0, 0),
    SNARE("snare", "Snare / Clap (erkannt)", 0, 0),
    HAT("hat", "Hi-Hats (erkannt)", 0, 0),
    MELODY("melody", "Melodie / Neben-Sounds (erkannt)", 0, 0),
    CUSTOM("custom", "Eigener Bereich (Hz von/bis)", 0, 0);

    public final String id, label;
    final double lowHz, highHz;

    SoundSource(String id, String label, double lowHz, double highHz) {
        this.id = id;
        this.label = label;
        this.lowHz = lowHz;
        this.highHz = highHz;
    }

    public static SoundSource byId(String id) {
        for (SoundSource s : values()) if (s.id.equals(id)) return s;
        // Werte aus älteren Versionen
        return switch (id == null ? "" : id) {
            case "Gesamt" -> ALL;
            case "Mitten" -> MID;
            case "Höhen" -> HIGH;
            default -> BASS;
        };
    }

    /** Erkannter Klang statt Frequenzbereich. */
    boolean isEvent() {
        return this == KICK || this == SNARE || this == HAT || this == MELODY;
    }

    /** Art des Analyse-Ereignisses für erkannte Klänge, sonst -1. */
    int eventType() {
        return switch (this) {
            case KICK -> MusicAnalyzer.KICK;
            case SNARE -> MusicAnalyzer.SNARE;
            case HAT -> MusicAnalyzer.HAT;
            case MELODY -> MusicAnalyzer.ACCENT;
            default -> -1;
        };
    }

    /** Für Auswahllisten: id → Anzeigename. */
    public static Map<String, String> choices() {
        Map<String, String> m = new LinkedHashMap<>();
        for (SoundSource s : values()) m.put(s.id, s.label);
        return m;
    }

    /** Untere Grenze von Band b der 16 Spektrum-Bänder (40 Hz bis 16 kHz, logarithmisch). */
    static double bandEdge(int b) {
        return 40 * Math.pow(400, b / (double) Music.SPECTRUM);
    }

    /** Anteil jedes Spektrum-Bands am Bereich lo..hi (logarithmisch überlappend). */
    static double[] weights(double lo, double hi) {
        double[] w = new double[Music.SPECTRUM];
        lo = Math.max(40, Math.min(lo, hi));
        hi = Math.min(16000, Math.max(lo * 1.01, hi));
        double a = Math.log(lo), z = Math.log(hi);
        for (int b = 0; b < w.length; b++) {
            double from = Math.log(bandEdge(b)), to = Math.log(bandEdge(b + 1));
            w[b] = Math.max(0, Math.min(z, to) - Math.max(a, from)) / (to - from);
        }
        return w;
    }
}
