package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.MusicAnalyzer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spielt Aufzeichnungen von der Musikseite („Erkennung abstimmen“) durch die aktuelle Analyse und druckt den
 * Verlauf. Läuft nur, wenn Aufzeichnungen vorhanden sind (Ordner per -Dledstudio.recordings oder %APPDATA%).
 */
class RecordingReplayTest {
    @Test
    void replayRecordings() throws Exception {
        String dir = System.getProperty("ledstudio.recordings",
                System.getenv("APPDATA") == null ? "" : System.getenv("APPDATA") + "/LEDStudio/analyse");
        Path folder = Path.of(dir);
        Assumptions.assumeTrue(Files.isDirectory(folder), "keine Aufzeichnungen");
        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
        }
        Assumptions.assumeFalse(files.isEmpty(), "keine Aufzeichnungen");
        for (Path f : files) replay(f);
    }

    /** Aufzeichnen und Nachspielen passen zusammen. */
    @Test
    void recordingRoundTrip(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        de.knirpslarsi.ledstudio.audio.AnalysisRecorder r = new de.knirpslarsi.ledstudio.audio.AnalysisRecorder();
        MusicAnalyzer a = new MusicAnalyzer();
        Path file = r.start(tmp.resolve("probe.csv"));
        float[] pcm = MusicAnalyzerTest.song(1);
        float[] window = new float[2048];
        for (int start = 0; start + 2048 <= 40 * MusicAnalyzerTest.RATE; start += 1024) {
            System.arraycopy(pcm, start, window, 0, 2048);
            double stamp = 100 + (start + 2048.0) / MusicAnalyzerTest.RATE;
            double[] v = de.knirpslarsi.ledstudio.audio.AudioEngine.analyse(window, 1, MusicAnalyzerTest.RATE);
            a.feed(v, stamp, 1024.0 / MusicAnalyzerTest.RATE);
            r.record(v, stamp, 1024.0 / MusicAnalyzerTest.RATE, a);
            if (start == 32 * MusicAnalyzerTest.RATE) r.mark(stamp, "hier kommt der Drop");
        }
        r.stop();
        org.junit.jupiter.api.Assertions.assertTrue(Files.size(file) > 100_000);
        replay(file);
    }

    public static void replay(Path file) throws Exception {
        System.out.println("=== " + file.getFileName());
        MusicAnalyzer a = new MusicAnalyzer();
        List<MusicAnalyzer.Event> events = new ArrayList<>();
        long seen = -1;
        double start = Double.NaN, nextPrint = 0;
        int[] counts = new int[MusicAnalyzer.EVENT_NAMES.size()];
        StringBuilder marks = new StringBuilder();
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("#mark;")) {
                String[] m = line.split(";", 3);
                marks.append(String.format(" [Markierung %.0fs: %s]", Double.parseDouble(m[1]) - start, m.length > 2 ? m[2] : ""));
                continue;
            }
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] c = line.split(";");
            double stamp = Double.parseDouble(c[0]), hop = Double.parseDouble(c[1]);
            double[] v = new double[20];
            for (int i = 0; i < 20; i++) v[i] = Double.parseDouble(c[2 + i]);
            if (Double.isNaN(start)) start = stamp;
            a.feed(v, stamp, hop);
            events.clear();
            seen = a.poll(seen, events);
            for (MusicAnalyzer.Event e : events) if (e.strength() >= 1) counts[e.type()]++;
            double t = stamp - start;
            if (t >= nextPrint) {
                MusicAnalyzer.Scene s = a.scene();
                System.out.printf("%5.0fs %-8s Spannung %.2f Energie %.2f Pegel %5.1f dB  %5.1f BPM (%.2f)  K%2d S%2d H%2d N%2d D%d%s%n",
                        t, s.section().name(), s.tension(), s.energy(), 20 * Math.log10(v[0] + 1e-9), s.bpm(), s.confidence(),
                        counts[0], counts[1], counts[2], counts[3], counts[4], marks);
                java.util.Arrays.fill(counts, 0);
                marks.setLength(0);
                nextPrint += 2;
            }
        }
    }
}
