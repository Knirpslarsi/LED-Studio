package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.model.Project;

import java.util.ArrayList;
import java.util.List;

import static de.knirpslarsi.ledstudio.effects.ParamSpec.choice;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.num;

/** Die Musik-Animationen als normaler Effekt, damit jedes Gerät seine eigene bekommen kann. */
final class MusicEffect extends LegacyEffects.Simple {
    private final Music music = new Music();
    private final Project.Audio cfg = new Project.Audio();

    MusicEffect() {
        super("music", "Musik-Animation", "Reagiert auf Musik. Quelle wählst du unter „Musik“.", specs());
    }

    private static String[] pairs(List<String> values) {
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
                choice("band", "Reagiert auf", "Bass", pairs(Music.BANDS)),
                choice("palette", "Musikfarbe", "Lila", pairs(Music.PALETTES)),
                num("duration", "Dauer / Tempo (Sek.)", 0.8, 0.15, 4, 0.05),
                num("length", "Effektlänge", 1.2, 0.2, 4, 0.05),
                num("pause", "Pause danach (Sek.)", 0.1, 0, 3, 0.05),
                num("gain", "Empfindlichkeit", 4, 0.2, 20, 0.1),
                num("floor", "Rauschschwelle", 0.02, 0, 0.3, 0.005),
                num("minimum", "Grundhelligkeit", 0, 0, 1, 0.01),
                num("smoothing", "Glättung", 0.75, 0, 1, 0.01));
    }

    @Override
    public boolean needsAudio() {
        return true;
    }

    @Override
    public void render(Frame f) {
        Params p = f.p;
        cfg.mode = p.s("mode");
        cfg.band = p.s("band");
        cfg.palette = p.s("palette");
        cfg.duration = p.d("duration");
        cfg.length = p.d("length");
        cfg.pause = p.d("pause");
        cfg.gain = p.d("gain");
        cfg.floor = p.d("floor");
        cfg.minimum = p.d("minimum");
        cfg.smoothing = p.d("smoothing");
        music.render(f.layout, f.now, f.audio, f.audioStamp, cfg, f.out);
    }
}
