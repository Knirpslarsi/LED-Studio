package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.model.Project;

import java.util.ArrayList;
import java.util.List;

import static de.knirpslarsi.ledstudio.effects.ParamSpec.bool;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.choice;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.num;

/** Die Musik-Animationen als normaler Effekt, damit jedes Gerät seine eigene bekommen kann. */
final class MusicEffect extends LegacyEffects.Simple {
    private final Music music = new Music();
    private final Project.Audio cfg = new Project.Audio();

    MusicEffect() {
        super("music", "Musik-Animation", "Reagiert auf Musik oder auf einen festen Takt. Die Tonquelle wählst du unter „Musik“.", specs());
    }

    static String[] pairs(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            out.add(v);
            out.add(v);
        }
        return out.toArray(new String[0]);
    }

    private static List<ParamSpec> specs() {
        // Die Puls-Variante braucht einen Effekt darunter und gibt es dafür als „Musikreaktion“ im Feinschliff.
        List<String> modes = new ArrayList<>(Music.MODES);
        modes.remove("Puls (aktueller Effekt)");
        return List.of(
                choice("mode", "Animation", "Beat-Balken", pairs(modes)),
                choice("trigger", "Auslöser", "music", "music", "Musik", "bpm", "Fester Takt (ohne Ton)"),
                num("bpm", "Takt (Schläge pro Minute)", 120, 30, 240, 1).help("Nur beim festen Takt."),
                choice("band", "Reagiert auf", "Bass", pairs(Music.BANDS)),
                choice("palette", "Musikfarbe", "Lila", pairs(Music.PALETTES)),
                bool("overlap", "Jeden Beat zeigen (Läufe überlagern sich)", true)
                        .help("Aus: Während ein Lauf läuft, werden neue Beats ignoriert (wie früher)."),
                choice("origin", "Ringe starten in", "center", "center", "Mitte des Aufbaus", "fixture", "Mitte jedes Bauteils"),
                num("duration", "Dauer eines Laufs (Sek.)", 0.8, 0.15, 4, 0.05),
                num("length", "Effektlänge", 1.2, 0.2, 4, 0.05),
                num("pause", "Mindestabstand zwischen Beats (Sek.)", 0.1, 0, 3, 0.01),
                num("sensitivity", "Beat-Empfindlichkeit", 1, 0.2, 3, 0.05).help("Höher erkennt auch leisere Beats, zu hoch reagiert auf alles."),
                num("gain", "Verstärkung", 4, 0.2, 20, 0.1),
                num("floor", "Rauschschwelle", 0.02, 0, 0.3, 0.005),
                num("minimum", "Grundhelligkeit", 0, 0, 1, 0.01),
                num("smoothing", "Glättung", 0.75, 0, 1, 0.01));
    }

    @Override
    public boolean needsAudio() {
        return true;
    }

    @Override
    public void reset(CompiledLayout layout) {
        music.reset();
    }

    @Override
    public void render(Frame f) {
        Params p = f.p;
        cfg.mode = p.s("mode");
        cfg.trigger = p.s("trigger");
        cfg.bpm = p.d("bpm");
        cfg.band = p.s("band");
        cfg.palette = p.s("palette");
        cfg.overlap = p.b("overlap");
        cfg.origin = p.s("origin");
        cfg.duration = p.d("duration");
        cfg.length = p.d("length");
        cfg.pause = p.d("pause");
        cfg.sensitivity = p.d("sensitivity");
        cfg.gain = p.d("gain");
        cfg.floor = p.d("floor");
        cfg.minimum = p.d("minimum");
        cfg.smoothing = p.d("smoothing");
        // Beim festen Takt zählt die Effektzeit, damit der Tempo-Regler auch hier wirkt.
        music.render(f.layout, "bpm".equals(cfg.trigger) ? f.t : f.now, f.audio, f.audioStamp, cfg, f.out);
    }

    /** Ringe im festen Takt, ganz ohne Musik. */
    static final class PulseRings extends LegacyEffects.Simple {
        private final Music music = new Music();
        private final Project.Audio cfg = new Project.Audio();

        PulseRings() {
            super("pulse_rings", "Pulsringe", "Ringe laufen im Takt von der Mitte nach außen, aus dem ganzen Aufbau oder aus jedem Bauteil.",
                    List.of(num("bpm", "Takt (Schläge pro Minute)", 90, 10, 240, 1),
                            choice("origin", "Ringe starten in", "center", "center", "Mitte des Aufbaus", "fixture", "Mitte jedes Bauteils"),
                            choice("palette", "Farbe", "Lila", pairs(Music.PALETTES)),
                            num("duration", "Laufzeit eines Rings (Sek.)", 1.4, 0.15, 6, 0.05),
                            num("length", "Ringbreite", 1.4, 0.2, 4, 0.05),
                            num("minimum", "Grundhelligkeit", 0.03, 0, 1, 0.01)));
        }

        @Override
        public void reset(CompiledLayout layout) {
            music.reset();
        }

        @Override
        public void render(Frame f) {
            cfg.mode = "Beat-Ringe";
            cfg.trigger = "bpm";
            cfg.overlap = true;
            cfg.pause = 0;
            cfg.bpm = f.p.d("bpm");
            cfg.origin = f.p.s("origin");
            cfg.palette = f.p.s("palette");
            cfg.duration = f.p.d("duration");
            cfg.length = f.p.d("length");
            cfg.minimum = f.p.d("minimum");
            music.render(f.layout, f.t, new double[0], 0, cfg, f.out);
        }
    }
}
