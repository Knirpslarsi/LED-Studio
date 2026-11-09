package de.knirpslarsi.ledstudio.audio;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Hört der Musik zu und beschreibt sie: Tempo und Taktposition, Energie, Spannung (Aufbau vor einem Drop), Drops,
 * ruhige Stellen und einzelne Klänge (Kick, Snare, Hi-Hat, kleine Neben-Sounds). Läuft im Audio-Thread mit jeder
 * Messung; Effekte lesen nur die Ergebnisse. Alles ist relativ zur Lautstärke des Songs, leise und laute Musik
 * werden gleich gut erkannt.
 */
public final class MusicAnalyzer {
    public enum Section {
        SILENCE("Stille"), CALM("Ruhig"), GROOVE("Groove"), BUILD("Spannungsaufbau"), DROP("Drop");

        public final String label;

        Section(String label) {
            this.label = label;
        }
    }

    public static final int KICK = 0, SNARE = 1, HAT = 2, ACCENT = 3, DROP_HIT = 4;
    public static final List<String> EVENT_NAMES = List.of("Kick", "Snare", "Hi-Hat", "Neben-Sound", "Drop");

    /** Ein einzelner Klang. strength: 1 = normal erkannt, darunter schwach. pitch: Lage im Spektrum, 0 tief .. 1 hoch. */
    public record Event(long seq, double time, int type, double strength, double pitch) {
    }

    /** Momentaufnahme der Analyse. Zeiten in Sekunden auf der Uhr von {@code System.nanoTime() / 1e9}. */
    public record Scene(double time, Section section, double sectionSince, double energy, double tension, double bass,
                        double brightness, double density, double bpm, double confidence, double beatTime,
                        long beatNumber, double downbeatTime, double lastDrop) {
        public static final Scene EMPTY = new Scene(Double.NEGATIVE_INFINITY, Section.SILENCE, 0, 0, 0, 0, 0.5, 0,
                120, 0, 0, 0, 0, Double.NEGATIVE_INFINITY);

        public double period() {
            return 60 / Math.max(30, bpm);
        }

        /** Fortlaufender Schlagzähler mit Nachkommastellen; springt nicht, damit Animationen glatt weiterlaufen. */
        public double beat(double now) {
            return beatNumber + (now - beatTime) / period();
        }

        /** Position im Takt (0..4). */
        public double barBeat(double now) {
            double b = (now - downbeatTime) / period();
            return b - Math.floor(b / 4) * 4;
        }

        /** Gleiche Szene, nur als Stille (z. B. wenn kein Ton mehr kommt). */
        public Scene silent(double now) {
            return new Scene(now, Section.SILENCE, sectionSince, 0, tension * 0.5, 0, brightness, 0, bpm, 0, beatTime,
                    beatNumber, downbeatTime, lastDrop);
        }
    }

    private static final int BANDS = Music.SPECTRUM;
    private static final int RING = 256;
    private static final double TEMPO_SECONDS = 6, PHASE_SECONDS = 4, TREND_STEP = 0.125;
    private static final int TREND = 64, TREND_FIT = 48;

    /** Findet Spitzen im Anstieg eines Frequenzbereichs, ein Bild verzögert. */
    private static final class Onset {
        final double refractory;
        double mean, dev, prev, prev2, prevStamp, lastHit = Double.NEGATIVE_INFINITY;

        Onset(double refractory) {
            this.refractory = refractory;
        }

        /** Stärke der Spitze im vorigen Bild (Fluss / Schwelle) oder 0. */
        double step(double flux, double stamp, double dt) {
            double threshold = mean + 2.0 * dev + 0.25;
            double hit = 0;
            if (prev > prev2 && prev >= flux && prevStamp - lastHit >= refractory) {
                double strength = prev / threshold;
                if (strength >= 0.6) {
                    hit = strength;
                    lastHit = prevStamp;
                }
            }
            double a = 1 - Math.exp(-dt / 0.8);
            mean += (flux - mean) * a;
            dev += (Math.abs(flux - mean) - dev) * a;
            prev2 = prev;
            prev = flux;
            prevStamp = stamp;
            return hit;
        }
    }

    private final AtomicReferenceArray<Event> events = new AtomicReferenceArray<>(RING);
    private volatile long nextSeq;
    private volatile Scene scene = Scene.EMPTY;
    /** Zahl der erkannten Klänge je Art, nur zur Anzeige. */
    public final int[] counts = new int[EVENT_NAMES.size()];

    private final double[] logPrev = new double[BANDS], bandPeak = new double[BANDS], flux = new double[BANDS],
            powPrev = new double[BANDS];
    private final Onset low = new Onset(0.14), mid = new Onset(0.11), high = new Onset(0.06);
    private double prevLow, prevMid, prevHigh, prevBody, prevTone, prevAir, prevSub, audibleFor;
    private int prevMidBand, prevHighBand;
    private double lastStamp = Double.NaN, anchor;

    // Tempo
    private final double[] env = new double[512];
    private int envCount, sinceTempo;
    private double bpm, pendingBpm, confidence, nextBeat = Double.NaN;
    private int pendingCount;
    private long beatIndex;
    private final double[] downbeatScore = new double[4];
    private int downbeatOffset;
    private double recentLow;

    // Energie und Verlauf
    /**
     * Bezug für „laut“: typische, fertig abgemischte Musik liegt bei etwa -20 dBFS (RMS) oder lauter. Der Bezug folgt
     * lauteren Songs sofort und sinkt nur langsam (1 dB in 12 s), nie unter -30 dBFS. So bleibt ein leises Intro
     * auch wirklich leise, statt nach ein paar Sekunden als „voll“ zu gelten.
     */
    static final double REFERENCE_DB = -20, REFERENCE_FLOOR_DB = -30, SPAN_DB = 24;
    private double peakDb = REFERENCE_DB, bassPeakDb = -40, highPeakDb = -40;
    private double loudPow, energy, energySlow, bassFast, bassSlow, highE, brightness = 0.5, density, silentFor = 10;
    private final double[] bassHistory = new double[128], energyHistory = new double[128];
    private int historyCount;
    private final double[][] trend = new double[5][TREND];
    private int trendCount;
    private double trendClock, rise;
    private double tension, lastDrop = Double.NEGATIVE_INFINITY;
    private Section section = Section.SILENCE, pending = Section.SILENCE;
    private double sectionSince, pendingFor;

    public Scene scene() {
        return scene;
    }

    /** Nummer des neuesten Ereignisses, -1 wenn es noch keins gab. */
    public long latestSeq() {
        return nextSeq - 1;
    }

    /** Hängt alle Ereignisse nach {@code after} an und gibt die Nummer des neuesten zurück. */
    public long poll(long after, List<Event> out) {
        long end = nextSeq;
        for (long s = Math.max(after + 1, end - RING); s < end; s++) {
            Event e = events.get((int) (s % RING));
            if (e != null && e.seq() == s) out.add(e);
        }
        return Math.max(after, end - 1);
    }

    private void emit(double time, int type, double strength, double pitch) {
        long seq = nextSeq;
        events.set((int) (seq % RING), new Event(seq, time, type, strength, pitch));
        nextSeq = seq + 1;
        if (strength >= 1) {
            counts[type]++;
            if (type != DROP_HIT) density += 1;
        }
    }

    /**
     * Eine Messung. values: Gesamt, Bass, Mitten, Höhen und 16 Spektrum-Bänder (Amplituden), stamp: Zeit der
     * Messung, hop: Abstand zweier Messungen in Sekunden.
     */
    public void feed(double[] values, double stamp, double hop) {
        if (values.length < 4 + BANDS) return;
        if (Double.isNaN(lastStamp) || stamp - lastStamp > 1 || stamp < lastStamp) restart(stamp);
        double dt = Double.isNaN(lastStamp) ? hop : Math.max(1e-3, Math.min(0.25, stamp - lastStamp));
        lastStamp = stamp;

        // Anstieg je Band in Log-Einheiten: misst Verhältnisse, also unabhängig von der Lautstärke.
        int loudest = 0;
        double totalPower = 0, weighted = 0, body = 0, tone = 0, air = 0, sub = 0;
        for (int b = 0; b < BANDS; b++) {
            double v = values[4 + b];
            bandPeak[b] = Math.max(v, bandPeak[b] * Math.exp(-dt / 8));
            double lv = Math.log(v + 1e-5);
            boolean audible = v > 1e-4 && v > bandPeak[b] * 0.01;
            flux[b] = audible ? Math.max(0, lv - logPrev[b]) : 0;
            logPrev[b] = lv;
            double p = v * v;
            // Zuwachs an Leistung: Snare hat Körper (130–800 Hz), Hi-Hats nur Luft (ab 3,5 kHz), Töne liegen dazwischen.
            double inc = Math.max(0, p - powPrev[b]);
            powPrev[b] = p;
            if (b < 3) sub += inc;
            if (b >= 3 && b < 8) body += inc;
            if (b >= 4 && b < 11) tone += inc;
            if (b >= 11) air += inc;
            totalPower += p;
            weighted += p * b;
            if (v > values[4 + loudest]) loudest = b;
        }
        double lowFlux = group(0, 4), midFlux = group(4, 11), highFlux = group(11, BANDS);
        int midBand = strongest(4, 11), highBand = strongest(11, BANDS);

        double sLow = low.step(lowFlux, stamp, dt), sMid = mid.step(midFlux, stamp, dt), sHigh = high.step(highFlux, stamp, dt);
        double hitTime = stamp - dt;
        // Eine Kick hat ihre Kraft unter 120 Hz. Snare und Toms schieben zwar auch etwas Tiefe an, aber mehr Körper darüber.
        boolean kick = sLow > 0 && prevSub > prevBody * 0.6;
        if (kick) emit(hitTime, KICK, sLow, 0);
        if (sMid > 0 || sHigh > 0) {
            boolean noisy = prevAir > prevTone * 0.5;
            boolean snare = noisy && prevBody > prevAir * 0.05 && sMid > 0;
            double strength = Math.max(sMid, sHigh);
            if (snare && (!kick || sMid >= 1)) emit(hitTime, SNARE, sMid, prevMidBand / (double) (BANDS - 1));
            else if (!kick && noisy) emit(hitTime, HAT, strength, prevHighBand / (double) (BANDS - 1));
            else if (!kick && sMid > 0) emit(hitTime, ACCENT, sMid, prevMidBand / (double) (BANDS - 1));
        }
        prevLow = lowFlux;
        prevMid = midFlux;
        prevHigh = highFlux;
        prevMidBand = midBand;
        prevHighBand = highBand;
        prevBody = body;
        prevSub = sub;
        prevTone = tone;
        prevAir = air;
        recentLow = Math.max(lowFlux, recentLow * Math.exp(-dt / 0.06));

        levels(values, dt, totalPower, weighted);
        tempo(lowFlux + midFlux * 0.6 + highFlux * 0.3, stamp, hop);
        trends(stamp, dt);
        sections(stamp, dt);

        // beatIndex zählt den nächsten Schlag; der letzte hatte beatIndex - 1.
        // Ohne erkanntes Tempo läuft das Gitter mit 120 BPM ab dem Start weiter, statt stillzustehen.
        double downbeat = Double.isNaN(nextBeat) ? anchor : nextBeat - 60 / bpmOr120()
                * (1 + Math.floorMod(beatIndex - 1 - downbeatOffset, 4));
        scene = new Scene(stamp, section, sectionSince, energy, tension, bassFast, brightness, density / 2,
                bpmOr120(), confidence, Double.isNaN(nextBeat) ? anchor : nextBeat - 60 / bpmOr120(),
                Double.isNaN(nextBeat) ? 0 : beatIndex - 1, downbeat, lastDrop);
    }

    private double bpmOr120() {
        return bpm > 0 ? bpm : 120;
    }

    private void restart(double stamp) {
        Arrays.fill(logPrev, Math.log(1e-5));
        Arrays.fill(env, 0);
        envCount = 0;
        historyCount = 0;
        trendCount = 0;
        nextBeat = Double.NaN;
        confidence = 0;
        sectionSince = stamp;
        anchor = stamp;
    }

    private double group(int from, int to) {
        double sum = 0, max = 0;
        for (int b = from; b < to; b++) {
            sum += flux[b];
            max = Math.max(max, flux[b]);
        }
        return (sum / (to - from) + max) / 2;
    }

    private int strongest(int from, int to) {
        int best = from;
        for (int b = from; b < to; b++) if (flux[b] > flux[best]) best = b;
        return best;
    }

    private static double db(double amplitude) {
        return 20 * Math.log10(amplitude + 1e-6);
    }

    private static double ease(double value, double target, double dt, double tau) {
        return value + (target - value) * (1 - Math.exp(-dt / tau));
    }

    /** Pegel relativ zum lautesten Stück der letzten Zeit (30 dB Spanne), Klangfarbe und Dichte. */
    private void levels(double[] v, double dt, double totalPower, double weighted) {
        double d = db(v[0]), bd = db(v[1]), hd = db(v[3]);
        // Lautheit über etwa eine halbe Sekunde: einzelne Schläge sind lauter als das, was man als Pegel hört.
        loudPow = ease(loudPow, v[0] * v[0], dt, 0.35);
        peakDb = Math.max(10 * Math.log10(loudPow + 1e-12), Math.max(REFERENCE_FLOOR_DB, peakDb - dt / 12));
        bassPeakDb = Math.max(bd, Math.max(REFERENCE_FLOOR_DB - 6, bassPeakDb - dt / 8));
        highPeakDb = Math.max(hd, Math.max(REFERENCE_FLOOR_DB - 10, highPeakDb - dt / 8));
        boolean silent = v[0] < 0.0015 || d < peakDb - 40;
        silentFor = silent ? silentFor + dt : 0;
        audibleFor = silent ? 0 : audibleFor + dt;
        double loud = 10 * Math.log10(loudPow + 1e-12);
        double e = silent ? 0 : clamp((loud - (peakDb - SPAN_DB)) / SPAN_DB);
        double b = silent ? 0 : clamp((bd - (bassPeakDb - SPAN_DB)) / SPAN_DB);
        double h = silent ? 0 : clamp((hd - (highPeakDb - SPAN_DB)) / SPAN_DB);
        energy = ease(energy, e, dt, 0.2);
        energySlow = ease(energySlow, e, dt, 2.5);
        bassFast = ease(bassFast, b, dt, 0.12);
        bassSlow = ease(bassSlow, b, dt, 2.5);
        highE = ease(highE, h, dt, 0.4);
        if (totalPower > 1e-9 && !silent) brightness = ease(brightness, weighted / totalPower / (BANDS - 1), dt, 0.5);
        density *= Math.exp(-dt / 2);
        bassHistory[historyCount % bassHistory.length] = bassFast;
        energyHistory[historyCount % energyHistory.length] = energy;
        historyCount++;
    }

    // ---- Tempo und Taktgitter

    private void tempo(double onset, double stamp, double hop) {
        env[envCount % env.length] = onset;
        envCount++;
        if (++sinceTempo >= 12 && envCount * hop >= 3) {
            sinceTempo = 0;
            estimateTempo(hop);
            alignPhase(stamp, hop);
        }
        if (Double.isNaN(nextBeat)) return;
        double period = 60 / bpmOr120();
        while (stamp >= nextBeat) {
            // Bass auf dem Schlag merken: Der stärkste Schlag im Takt wird die Eins.
            int slot = (int) Math.floorMod(beatIndex, 4);
            for (int i = 0; i < 4; i++) downbeatScore[i] *= 0.94;
            downbeatScore[slot] += recentLow;
            int best = 0;
            for (int i = 1; i < 4; i++) if (downbeatScore[i] > downbeatScore[best]) best = i;
            if (downbeatScore[best] > downbeatScore[downbeatOffset] * 1.15) downbeatOffset = best;
            beatIndex++;
            nextBeat += period;
        }
    }

    private double envAt(int back) {
        return env[Math.floorMod(envCount - 1 - back, env.length)];
    }

    /** Autokorrelation der Anschläge: welcher Abstand wiederholt sich am stärksten. */
    private void estimateTempo(double hop) {
        int n = Math.min(envCount, Math.min(env.length, (int) (TEMPO_SECONDS / hop)));
        double[] x = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) mean += x[i] = envAt(n - 1 - i);
        mean /= n;
        double zero = 0;
        for (int i = 0; i < n; i++) {
            x[i] -= mean;
            zero += x[i] * x[i];
        }
        if (zero < 1e-6) {
            confidence = ease(confidence, 0, 0.25, 1);
            return;
        }
        int minLag = Math.max(2, (int) Math.floor(60 / 190.0 / hop)), maxLag = (int) Math.ceil(60 / 65.0 / hop);
        int top = Math.min(n - 2, 2 * maxLag + 2);
        double[] ac = new double[top + 1];
        for (int lag = 1; lag <= top; lag++) {
            double s = 0;
            for (int i = lag; i < n; i++) s += x[i] * x[i - lag];
            ac[lag] = s / (n - lag) / (zero / n);
        }
        double[] score = new double[maxLag + 2];
        int best = -1;
        for (int lag = minLag - 1; lag <= Math.min(maxLag + 1, top / 2); lag++) {
            if (lag < 1) continue;
            double tempo = 60 / (lag * hop);
            double prior = Math.exp(-0.5 * Math.pow(Math.log(tempo / 120) / Math.log(2) / 0.8, 2));
            score[lag] = (Math.max(0, ac[lag]) + 0.5 * Math.max(0, ac[2 * lag])) * prior;
            if (lag >= minLag && lag <= maxLag && (best < 0 || score[lag] > score[best])) best = lag;
        }
        if (best < 0) return;
        double offset = 0, a = score[best - 1], b = score[best], c = score[best + 1];
        if (a - 2 * b + c < 0) offset = Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / (a - 2 * b + c)));
        double candidate = 60 / ((best + offset) * hop);
        double conf = clamp(ac[best] * 1.2);
        confidence = ease(confidence, conf, 0.25, 1.0);
        if (conf < 0.15) return;
        if (bpm <= 0) {
            bpm = candidate;
        } else if (Math.abs(candidate / bpm - 1) < 0.05) {
            bpm += (candidate - bpm) * 0.3;
            pendingCount = 0;
        } else {
            if (Math.abs(candidate / Math.max(1, pendingBpm) - 1) < 0.05) pendingCount++;
            else {
                pendingBpm = candidate;
                pendingCount = 1;
            }
            // Ein neues Tempo erst übernehmen, wenn es sich etwa 2 Sekunden hält.
            if (pendingCount >= 8) {
                bpm = pendingBpm;
                pendingCount = 0;
            }
        }
    }

    /** Legt das Taktgitter so, dass möglichst viele Anschläge der letzten Sekunden auf Schläge fallen. */
    private void alignPhase(double stamp, double hop) {
        if (bpm <= 0) return;
        double periodFrames = 60 / bpm / hop;
        int n = Math.min(envCount, Math.min(env.length, (int) (PHASE_SECONDS / hop)));
        int steps = Math.max(1, (int) Math.ceil(periodFrames));
        double bestScore = -1, bestPhase = 0;
        for (int p = 0; p < steps; p++) {
            double s = 0, w = 1;
            for (double back = p; back < n - 1; back += periodFrames) {
                int i = (int) back;
                double f = back - i;
                s += w * (envAt(i) * (1 - f) + envAt(i + 1) * f);
                w *= 0.85;
            }
            if (s > bestScore) {
                bestScore = s;
                bestPhase = p;
            }
        }
        double period = 60 / bpm;
        double lastBeat = stamp - bestPhase * hop;
        if (Double.isNaN(nextBeat)) {
            nextBeat = lastBeat + period;
            while (nextBeat <= stamp) nextBeat += period;
            // Weiterzählen, wo das vorläufige 120-BPM-Gitter stand, damit Animationen nicht springen.
            beatIndex = Math.round((nextBeat - anchor) / (60 / bpmOr120()));
            return;
        }
        double predicted = nextBeat - period;
        double error = lastBeat - predicted;
        error -= Math.rint(error / period) * period;
        nextBeat += error * (confidence > 0.4 ? 0.35 : 0.15);
    }

    // ---- Verlauf, Spannung, Drops, Abschnitte

    private void trends(double stamp, double dt) {
        trendClock += dt;
        if (trendClock < TREND_STEP) return;
        trendClock -= TREND_STEP;
        double[] sample = {energy, brightness, density / 2, highE, bassSlow};
        for (int i = 0; i < sample.length; i++) trend[i][trendCount % TREND] = sample[i];
        trendCount++;
        // Erst wenn die Musik ein paar Sekunden läuft: Der Einstieg selbst ist kein Spannungsaufbau.
        if (trendCount < TREND_FIT || audibleFor < TREND_FIT * TREND_STEP + 1) {
            rise = 0;
            return;
        }
        double se = slope(0), sb = slope(1), sd = slope(2), sh = slope(3);
        double r = 4 * (Math.max(0, se) + 1.5 * Math.max(0, sb) + 0.25 * Math.max(0, sd) + 0.7 * Math.max(0, sh));
        // Typisch vor einem Drop: Der Bass ist raus, oben wird es immer voller.
        if (r > 0.25 && bassSlow < 0.5 && highE > 0.45) r += 0.25;
        rise = r;
    }

    /** Steigung pro Sekunde über die letzten Sekunden (kleinste Quadrate). */
    private double slope(int feature) {
        int n = Math.min(trendCount, TREND_FIT);
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double x = i * TREND_STEP, y = trend[feature][Math.floorMod(trendCount - n + i, TREND)];
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double den = n * sxx - sx * sx;
        return den <= 1e-9 ? 0 : (n * sxy - sx * sy) / den;
    }

    private double historyMean(double[] h, double fromAgo, double toAgo, double hop) {
        int a = (int) (toAgo / hop), b = (int) (fromAgo / hop);
        int count = 0;
        double sum = 0;
        for (int back = a; back <= b && back < historyCount && back < h.length; back++) {
            sum += h[Math.floorMod(historyCount - 1 - back, h.length)];
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private void sections(double stamp, double dt) {
        if (rise > 0.35) tension = Math.min(1, tension + dt * (rise - 0.2) * 0.12);
        else tension -= dt * tension / 3;
        if (silentFor > 4) tension = 0;

        double hop = Math.max(0.005, dt);
        double bassBefore = historyMean(bassHistory, 2.0, 0.6, hop), energyBefore = historyMean(energyHistory, 2.0, 0.6, hop);
        if (!Double.isNaN(bassBefore) && stamp - lastDrop > 6 && energyBefore > 0.12 && silentFor == 0) {
            double bassJump = bassFast - bassBefore, energyJump = energy - energyBefore;
            boolean drop = bassFast > 0.6 && energy > 0.55
                    && (bassJump > 0.45 || (bassJump > 0.3 && tension > 0.25) || (energyJump > 0.25 && tension > 0.5));
            if (drop) {
                lastDrop = stamp;
                emit(stamp, DROP_HIT, 1 + tension, 0);
                tension = 0;
                // Drops landen auf der Eins.
                if (!Double.isNaN(nextBeat)) {
                    double period = 60 / bpmOr120();
                    long nearest = (stamp - (nextBeat - period)) < period / 2 ? beatIndex - 1 : beatIndex;
                    int slot = (int) Math.floorMod(nearest, 4);
                    downbeatOffset = slot;
                    downbeatScore[slot] += 10;
                }
            }
        }

        double beats = 60 / bpmOr120();
        Section want;
        if (silentFor > 0.6) want = Section.SILENCE;
        else if (stamp - lastDrop < Math.max(8, 32 * beats) && energySlow > 0.5) want = Section.DROP;
        else if (tension > (section == Section.BUILD ? 0.2 : 0.35)) want = Section.BUILD;
        else if (energySlow < (section == Section.CALM ? 0.6 : 0.52) || (bassSlow < 0.3 && energySlow < 0.7)) want = Section.CALM;
        else want = Section.GROOVE;

        if (want == section) {
            pendingFor = 0;
            return;
        }
        if (want != pending) {
            pending = want;
            pendingFor = 0;
        }
        pendingFor += dt;
        // Ruhig und Groove wechseln erst nach 2 Sekunden, damit es bei kurzen Pausen nicht hin und her springt.
        double hold = want == Section.DROP || want == Section.SILENCE ? 0 : want == Section.BUILD ? 1.0 : 2.0;
        if (pendingFor >= hold) {
            section = want;
            sectionSince = stamp;
            pendingFor = 0;
        }
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
