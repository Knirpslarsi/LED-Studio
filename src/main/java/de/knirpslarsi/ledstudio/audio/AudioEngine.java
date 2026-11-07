package de.knirpslarsi.ledstudio.audio;

import de.knirpslarsi.ledstudio.model.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Nimmt Ton lokal auf und wertet ihn aus. Es wird nichts gespeichert oder übertragen. */
public final class AudioEngine {
    private static final Logger LOG = Logger.getLogger("Audio");
    private static final int BLOCK = 2048;

    private final Supplier<Project.Audio> settings;
    private volatile boolean running = true;
    private volatile boolean restart;
    private volatile boolean forced;
    /** Gesamt, Bass, Mitten, Höhen. */
    public volatile double[] values = new double[4];
    public volatile double stamp;
    public volatile String status = "Soundreaktion aus";

    public AudioEngine(Supplier<Project.Audio> settings) {
        this.settings = settings;
    }

    public void start() {
        Thread t = new Thread(this::run, "audio");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }

    /** Hält die Aufnahme auch ohne globale Soundreaktion an, weil eine Ebene sie braucht. */
    public void setForced(boolean on) {
        forced = on;
    }

    public void restart() {
        restart = true;
    }

    public static Map<String, String> devices() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("default", "PC-Sound · Standardausgabe");
        try {
            out.putAll(Wasapi.devices());
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "Audiogeräte nicht lesbar", e);
        }
        return out;
    }

    private void run() {
        while (running) {
            Project.Audio cfg = settings.get();
            if (!cfg.enabled && !forced) {
                status = "Soundreaktion aus";
                values = new double[4];
                sleep(150);
                continue;
            }
            String device = cfg.device;
            try (Wasapi.Capture capture = new Wasapi.Capture(device)) {
                restart = false;
                status = "Audio aktiv: " + capture.name;
                float[] window = new float[BLOCK * capture.channels];
                int filled = 0;
                ArrayList<float[]> packets = new ArrayList<>();
                while (running && !restart) {
                    Project.Audio now = settings.get();
                    if ((!now.enabled && !forced) || !now.device.equals(device)) break;
                    packets.clear();
                    capture.read(packets);
                    if (packets.isEmpty()) {
                        sleep(5);
                        continue;
                    }
                    for (float[] p : packets) {
                        int off = 0;
                        while (off < p.length) {
                            int n = Math.min(p.length - off, window.length - filled);
                            System.arraycopy(p, off, window, filled, n);
                            filled += n;
                            off += n;
                            if (filled == window.length) {
                                values = analyse(window, capture.channels, capture.rate);
                                stamp = System.nanoTime() / 1e9;
                                filled = 0;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                values = new double[4];
                status = "Audiofehler: " + e.getMessage() + " – Quelle prüfen";
                LOG.log(Level.WARNING, "Audio", e);
                sleep(2000);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Pegel: Gesamt-RMS und Bass (30–250 Hz), Mitten (–2 kHz), Höhen (–16 kHz). Länge muss eine Zweierpotenz × Kanäle sein. */
    public static double[] analyse(float[] interleaved, int channels, int rate) {
        int n = interleaved.length / channels;
        double[] power = new double[n / 2 + 1];
        double[] re = new double[n], im = new double[n], window = new double[n];
        double windowSum = 0;
        for (int i = 0; i < n; i++) {
            window[i] = n == 1 ? 1 : 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            windowSum += window[i] * window[i];
        }
        double rmsSum = 0;
        for (int ch = 0; ch < channels; ch++) {
            double mean = 0;
            for (int i = 0; i < n; i++) {
                float v = interleaved[i * channels + ch];
                mean += Float.isFinite(v) ? v : 0;
            }
            mean /= n;
            for (int i = 0; i < n; i++) {
                float raw = interleaved[i * channels + ch];
                double v = (Float.isFinite(raw) ? raw : 0) - mean;
                rmsSum += v * v;
                re[i] = v * window[i];
                im[i] = 0;
            }
            fft(re, im);
            for (int i = 0; i < power.length; i++) power[i] += (re[i] * re[i] + im[i] * im[i]) / channels;
        }
        double[] out = new double[4];
        out[0] = Math.sqrt(rmsSum / (n * channels));
        double norm = Math.max(windowSum * n, 1);
        int[][] bands = {{30, 250}, {250, 2000}, {2000, 16000}};
        for (int b = 0; b < 3; b++) {
            double sum = 0;
            for (int i = 0; i < power.length; i++) {
                double f = (double) i * rate / n;
                if (f >= bands[b][0] && f < bands[b][1]) sum += power[i];
            }
            out[b + 1] = Math.sqrt(2 * sum / norm);
        }
        return out;
    }

    /** Radix-2-FFT. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double next = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }
}
