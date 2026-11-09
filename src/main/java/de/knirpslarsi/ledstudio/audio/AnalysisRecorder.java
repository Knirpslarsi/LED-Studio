package de.knirpslarsi.ledstudio.audio;

import de.knirpslarsi.ledstudio.util.AppPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schreibt die Messwerte der Musik-Analyse in eine Datei, damit sich die Erkennung an echter Musik nachstellen und
 * abstimmen lässt. Gespeichert werden nur 20 Pegel je Messung (Gesamt, Bass, Mitten, Höhen, 16 Bänder), daraus lässt
 * sich der Ton nicht zurückgewinnen.
 */
public final class AnalysisRecorder {
    private static final Logger LOG = Logger.getLogger("Audio");
    private final Object lock = new Object();
    private BufferedWriter out;
    private Path file;
    private long seen = Long.MIN_VALUE;
    private final List<MusicAnalyzer.Event> events = new ArrayList<>();
    private double lastFlush;

    public static Path folder() {
        return AppPaths.dataDir().resolve("analyse");
    }

    public boolean active() {
        synchronized (lock) {
            return out != null;
        }
    }

    public Path file() {
        synchronized (lock) {
            return file;
        }
    }

    public Path start() throws IOException {
        Files.createDirectories(folder());
        return start(folder().resolve("analyse-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".csv"));
    }

    public Path start(Path target) throws IOException {
        synchronized (lock) {
            stop();
            file = target;
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            out.write("# LED Studio Musik-Analyse. Zeilen: Zeit;Abstand;20 Pegel;Abschnitt;Spannung;Energie;BPM;Sicherheit\n");
            out.write("# #event;Zeit;Art;Stärke;Tonhöhe  und  #mark;Zeit;Notiz\n");
            seen = Long.MIN_VALUE;
            return file;
        }
    }

    public void stop() {
        synchronized (lock) {
            if (out == null) return;
            try {
                out.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Aufzeichnung schließen", e);
            }
            out = null;
        }
    }

    /** Markierung vom Nutzer, z. B. „hier war ein Drop“. */
    public void mark(double stamp, String note) {
        synchronized (lock) {
            write(String.format(Locale.ROOT, "#mark;%.3f;%s%n", stamp, note.replace(';', ',').replace('\n', ' ')));
        }
    }

    /** Aus dem Audio-Thread nach jeder Messung. */
    public void record(double[] values, double stamp, double hop, MusicAnalyzer analyzer) {
        synchronized (lock) {
            if (out == null) return;
            if (seen == Long.MIN_VALUE) seen = analyzer.latestSeq();
            events.clear();
            seen = analyzer.poll(seen, events);
            StringBuilder line = new StringBuilder(256);
            for (MusicAnalyzer.Event e : events) {
                line.append(String.format(Locale.ROOT, "#event;%.3f;%s;%.2f;%.2f%n", e.time(), MusicAnalyzer.EVENT_NAMES.get(e.type()), e.strength(), e.pitch()));
            }
            line.append(String.format(Locale.ROOT, "%.4f;%.5f", stamp, hop));
            for (double v : values) line.append(';').append(String.format(Locale.ROOT, "%.6g", v));
            MusicAnalyzer.Scene s = analyzer.scene();
            line.append(String.format(Locale.ROOT, ";%s;%.3f;%.3f;%.1f;%.2f%n", s.section().name(), s.tension(), s.energy(), s.bpm(), s.confidence()));
            write(line.toString());
            if (stamp - lastFlush > 2) {
                lastFlush = stamp;
                try {
                    out.flush();
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Aufzeichnung", e);
                }
            }
        }
    }

    private void write(String text) {
        if (out == null) return;
        try {
            out.write(text);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Aufzeichnung abgebrochen", e);
            stop();
        }
    }
}
