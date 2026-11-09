package de.knirpslarsi.ledstudio.audio;

import java.util.List;

/**
 * Ein Musiksignal für eine Ebene: folgt einem Frequenzbereich, der Lautstärke oder erkannten Klängen und liefert
 * 0..1. Jeder Bereich ist auf seine eigene Spitze der letzten Sekunden bezogen, schlägt also unabhängig von der
 * Lautstärke des Songs voll aus.
 */
public final class SoundChannel {
    /** Einstellungen des Signals. */
    public static final class Settings {
        public SoundSource source = SoundSource.BASS;
        public double fromHz = 200, toHz = 2000, threshold = 0.1, contrast = 1.6, boost = 1, attackMs = 25, releaseMs = 180;
        public double sensitivity = 1;
    }

    private double peak, allPeak, prevRaw, target, level, stamp = Double.NaN;

    public double level() {
        return level;
    }

    public void reset() {
        peak = allPeak = prevRaw = target = level = 0;
        stamp = Double.NaN;
    }

    /** Gesamtamplitude der 16 Bänder. */
    static double spectrum(double[] values) {
        double sum = 0;
        for (int b = 0; b < Music.SPECTRUM; b++) sum += values[4 + b] * values[4 + b];
        return Math.sqrt(sum);
    }

    /** Schwelle, Kontrast und Verstärkung auf einen Wert 0..1 anwenden. */
    static double shape(double x, double threshold, double contrast, double boost) {
        double t = Math.max(0, Math.min(1, threshold));
        x = Math.max(0, (x - t) / Math.max(0.05, 1 - t));
        return Math.min(1, Math.pow(x, Math.max(0.2, contrast)) * Math.max(0, boost));
    }

    /**
     * Ein Bild weiter. values/valueStamp: letzte Messung der Aufnahme, now: jetzige Zeit, dt: Sekunden seit dem
     * letzten Bild, events: neue erkannte Klänge seit dem letzten Bild.
     */
    public double update(Settings s, double[] values, double valueStamp, double now, double dt, List<MusicAnalyzer.Event> events) {
        double release = Math.max(0.01, s.releaseMs / 1000), attack = Math.max(0.001, s.attackMs / 1000);
        if (s.source.isEvent()) {
            target *= Math.exp(-dt / release);
            for (MusicAnalyzer.Event e : events) {
                if (e.type() == s.source.eventType() && e.strength() * Math.max(0.05, s.sensitivity) >= 1) {
                    target = Math.min(1, Math.max(0, s.boost));
                }
            }
        } else if (values == null || values.length < 4 + Music.SPECTRUM || now - valueStamp > 0.5) {
            target = 0;
        } else if (valueStamp != stamp) {
            double adt = Double.isNaN(stamp) ? 0.021 : Math.max(0.005, Math.min(0.25, valueStamp - stamp));
            stamp = valueStamp;
            double amp;
            if (s.source == SoundSource.ALL) {
                amp = values[0];
            } else {
                double[] w = s.source == SoundSource.CUSTOM ? SoundSource.weights(s.fromHz, s.toHz)
                        : SoundSource.weights(s.source.lowHz, s.source.highHz);
                double sum = 0;
                for (int b = 0; b < Music.SPECTRUM; b++) sum += w[b] * values[4 + b] * values[4 + b];
                amp = Math.sqrt(sum);
            }
            if (s.source == SoundSource.VOICE) {
                // Stimmen halten Töne: kurze Schläge (ein Messfenster lang) zählen nur zum Teil.
                double raw = amp;
                amp = Math.min(amp, prevRaw * 1.3 + amp * 0.3);
                prevRaw = raw;
            }
            peak = Math.max(amp, peak * Math.exp(-adt / 2.5));
            // Bezug: die eigene Spitze, aber mindestens ein Viertel des ganzen Spektrums. Sonst gälte ein Bereich, der
            // dauerhaft leise ist, als voll.
            allPeak = Math.max(spectrum(values), allPeak * Math.exp(-adt / 2.5));
            double ref = Math.max(peak, allPeak * 0.25);
            target = shape(amp / Math.max(1e-7, ref), s.threshold, s.contrast, s.boost);
        }
        double k = 1 - Math.exp(-dt / (target > level ? attack : release));
        level += (target - level) * k;
        return level;
    }
}
