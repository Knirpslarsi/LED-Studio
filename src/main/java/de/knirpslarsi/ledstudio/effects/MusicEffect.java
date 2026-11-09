package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.audio.SoundSource;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.model.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.knirpslarsi.ledstudio.effects.ParamSpec.bool;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.choice;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.num;

/** Die Musik-Animationen als normaler Effekt, damit jedes Gerät seine eigene bekommen kann. */
final class MusicEffect extends LegacyEffects.Simple {
    private final Music music = new Music();
    private final Project.Audio cfg = new Project.Audio();

    MusicEffect() {
        super("music", "Musik-Animation", "Reagiert auf Musik oder auf einen festen Takt. „Auto-Regie“ analysiert den Song und "
                + "wechselt passend zum Song-Teil zwischen Effekten wie Energie, Fluss und Spektrum-Welle. Die Tonquelle "
                + "wählst du unter „Musik“.", specs());
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
        List<String> reactive = new ArrayList<>(Music.stripModes());
        reactive.add(0, Music.AUTO);
        List<String> classic = new ArrayList<>(modes);
        classic.removeAll(reactive);
        List<String> wave = List.of(Music.AUTO, "Spektrum-Welle");
        List<String> flow = List.of(Music.AUTO, "Fluss", "Power", "Regen");
        Map<String, String> sources = SoundSource.choices();
        String[] src = new String[sources.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            src[i++] = e.getKey();
            src[i++] = e.getValue();
        }
        return List.of(
                choice("mode", "Animation", Music.AUTO, pairs(modes)).section("Animation"),
                choice("trigger", "Auslöser", "music", "music", "Musik", "bpm", "Fester Takt (ohne Ton, Vorführung)"),
                num("bpm", "Takt (Schläge pro Minute)", 120, 30, 240, 1).showIf("trigger", List.of("bpm")),
                choice("palette", "Musikfarbe / Farbverlauf", "Lila", pairs(Music.PALETTES)),
                choice("spread", "Verteilung", "auto", "auto", "Automatisch", "chain", "Entlang der Kette",
                        "fixture", "Jeder Lüfter für sich", "x", "Von links nach rechts").showIf("mode", reactive),

                choice("react1", "Kanal 1 reagiert auf", "bass", src).section("Worauf reagiert der Effekt?").showIf("mode", reactive)
                        .help("Hauptsignal. Energie: erster Balken, Fluss: erste Farbe, Power: Füllung, Regen: große Tropfen, "
                                + "Pegel, Takt-Balken und Farbblöcke: Füllstand bzw. Helligkeit."),
                choice("react2", "Kanal 2 reagiert auf", "voice", src).showIf("mode", reactive)
                        .help("Energie: zweiter Balken, Fluss: zweite Farbe, Power: große Funken, Regen: mittlere Tropfen."),
                choice("react3", "Kanal 3 reagiert auf", "high", src).showIf("mode", reactive)
                        .help("Energie: dritter Balken, Fluss: dritte Farbe, Power: kleine Funken, Regen: kleine Tropfen."),
                num("fromHz", "Eigener Bereich von (Hz)", 200, 40, 16000, 10).showIf("mode", reactive)
                        .help("Nur für Kanäle mit „Eigener Bereich“."),
                num("toHz", "Eigener Bereich bis (Hz)", 2000, 40, 16000, 10).showIf("mode", reactive),
                num("specFrom", "Spektrum-Welle von (Hz)", 40, 40, 16000, 10).showIf("mode", wave),
                num("specTo", "Spektrum-Welle bis (Hz)", 16000, 40, 16000, 10).showIf("mode", wave),

                num("boost", "Verstärkung", 1, 0.2, 4, 0.05).section("Signal").showIf("mode", reactive)
                        .help("Über 1 erreichen auch schwächere Stellen den vollen Ausschlag."),
                num("threshold", "Schwelle", 0.1, 0, 0.9, 0.01).showIf("mode", reactive)
                        .help("Was darunter liegt, bleibt dunkel. Höher: nur die kräftigen Stellen leuchten."),
                num("contrast", "Kontrast", 1.6, 0.3, 4, 0.05).showIf("mode", reactive)
                        .help("Höher: Spitzen stechen stärker heraus, Leises wird dunkler."),
                num("attackMs", "Anstieg (ms)", 25, 1, 500, 1).showIf("mode", reactive)
                        .help("Wie schnell das Licht auf einen Einsatz reagiert."),
                num("releaseMs", "Abfall (ms)", 180, 20, 2000, 5).showIf("mode", reactive)
                        .help("Wie lange es danach ausklingt."),
                num("sensitivity", "Empfindlichkeit der Erkennung", 1, 0.2, 3, 0.05)
                        .help("Für erkannte Kicks, Snares, Hi-Hats und Beats. Höher erkennt auch leisere, zu hoch reagiert auf alles."),

                bool("mirror", "Zur Mitte spiegeln", true).section("Aussehen").showIf("mode", reactive),
                bool("reverse", "Farbverlauf umkehren", false).showIf("mode", reactive),
                num("blur", "Weichzeichnen", 1, 0, 4, 1).showIf("mode", reactive),
                num("background", "Hintergrundleuchten", 0, 0, 0.4, 0.01).showIf("mode", reactive)
                        .help("Der Farbverlauf leuchtet leicht mit, auch wenn nichts kommt."),
                num("flowSpeed", "Geschwindigkeit", 1, 0.2, 4, 0.05).showIf("mode", flow)
                        .help("Wie schnell der Fluss nach außen wandert."),
                num("afterglow", "Nachleuchten", 1, 0.2, 4, 0.05).showIf("mode", flow)
                        .help("Wie lange Fluss, Funken und Tropfen nachglühen."),
                num("dynamics", "Leise Stellen dunkler", 0.9, 0, 1, 0.01).showIf("mode", reactive)
                        .help("Wie stark die Helligkeit der Lautstärke folgt. 0: immer volle Helligkeit."),
                num("minimum", "Grundhelligkeit", 0, 0, 1, 0.01),

                choice("band", "Reagiert auf", "Bass", pairs(Music.BANDS)).section("Beat-Animation").showIf("mode", classic),
                bool("overlap", "Jeden Beat zeigen (Läufe überlagern sich)", true).showIf("mode", classic)
                        .help("Aus: Während ein Lauf läuft, werden neue Beats ignoriert (wie früher)."),
                choice("origin", "Ringe starten in", "center", "center", "Mitte des Aufbaus", "fixture", "Mitte jedes Bauteils")
                        .showIf("mode", classic),
                num("duration", "Dauer eines Laufs (Sek.)", 0.8, 0.15, 4, 0.05).showIf("mode", classic),
                num("length", "Effektlänge", 1.2, 0.2, 4, 0.05).showIf("mode", classic),
                num("pause", "Mindestabstand zwischen Beats (Sek.)", 0.1, 0, 3, 0.01).showIf("mode", classic),
                num("gain", "Verstärkung", 4, 0.2, 20, 0.1).showIf("mode", classic),
                num("floor", "Rauschschwelle", 0.02, 0, 0.3, 0.005).showIf("mode", classic),
                num("smoothing", "Glättung", 0.75, 0, 1, 0.01).showIf("mode", classic));
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
        cfg.dynamics = p.d("dynamics");
        cfg.spread = p.s("spread");
        cfg.react1 = p.s("react1");
        cfg.react2 = p.s("react2");
        cfg.react3 = p.s("react3");
        cfg.fromHz = p.d("fromHz");
        cfg.toHz = p.d("toHz");
        cfg.specFrom = p.d("specFrom");
        cfg.specTo = p.d("specTo");
        cfg.boost = p.d("boost");
        cfg.threshold = p.d("threshold");
        cfg.contrast = p.d("contrast");
        cfg.attackMs = p.d("attackMs");
        cfg.releaseMs = p.d("releaseMs");
        cfg.mirror = p.b("mirror");
        cfg.reverse = p.b("reverse");
        cfg.blur = p.d("blur");
        cfg.background = p.d("background");
        cfg.flowSpeed = p.d("flowSpeed");
        cfg.afterglow = p.d("afterglow");
        music.setAnalyzer(f.analysis);
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
