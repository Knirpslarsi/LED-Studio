package de.knirpslarsi.ledstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Eine Lichtshow auf einer Zeitleiste: Spuren mit Clips, die zu festen Zeiten im Song laufen. Jede Spur hat ein Ziel
 * (alles, Geräte, Lüfter oder einzelne LEDs), jeder Clip einen Effekt mit Ein- und Ausblenden.
 */
public class Show {
    public static final String EXTENSION = ".ledshow";

    public String name = "Neue Show";
    /** Musikdatei (MP3 oder WAV), absoluter Pfad. Leer: Show ohne Song. */
    public String audio = "";
    /** Länge ohne Song in Sekunden. */
    public double length = 60;
    public List<Track> tracks = new ArrayList<>();
    /** Eigene Markierungen auf der Zeitleiste, z. B. „Refrain“. */
    public List<Marker> markers = new ArrayList<>();

    public static class Marker {
        public double time;
        public String name = "";

        public Marker() {
        }

        public Marker(double time, String name) {
            this.time = time;
            this.name = name;
        }
    }

    public static class Track {
        public String id = UUID.randomUUID().toString();
        public String name = "Spur";
        /** Wie bei Ebenen; zusätzlich "led:<bauteil-id>#<index>" für einzelne LEDs. Leer = alles. */
        public List<String> targets = new ArrayList<>();
        public boolean mute;
        public List<Clip> clips = new ArrayList<>();
    }

    public static class Clip {
        public String id = UUID.randomUUID().toString();
        public double start, end = 1;
        /** Ein- und Ausblenden in Sekunden. */
        public double fadeIn = 0.03, fadeOut = 0.15;
        /** Eigene LEDs dieses Clips (Schreibweise wie bei Spuren). Leer: das Ziel der Spur. */
        public List<String> targets = new ArrayList<>();
        /** Über 0: Die eigenen LEDs sind so viele zufällig gewählte LEDs der Spur („Neu würfeln“ wählt andere). */
        public int randomLeds;
        /** Effekt und Aussehen; Ziel kommt vom Clip oder von der Spur. */
        public Project.Layer look = Project.Layer.solid("#A020FF");

        /** Ziel, das wirklich gilt: eigene LEDs oder die der Spur. */
        public List<String> targetsIn(Track track) {
            return targets != null && !targets.isEmpty() ? targets : track.targets;
        }

        public double length() {
            return end - start;
        }

        /** Helligkeit durch Ein- und Ausblenden zur Zeit t (Sekunden im Song), 0 außerhalb. */
        public double envelope(double t) {
            if (t < start || t >= end) return 0;
            double in = fadeIn <= 0 ? 1 : Math.min(1, (t - start) / fadeIn);
            double out = fadeOut <= 0 ? 1 : Math.min(1, (end - t) / fadeOut);
            return Math.max(0, Math.min(in, out));
        }

        public Clip copy() {
            Clip c = Project.GSON.fromJson(Project.GSON.toJson(this), Clip.class);
            c.id = UUID.randomUUID().toString();
            c.look.id = UUID.randomUUID().toString();
            return c;
        }
    }

    /** Ende der letzten Spur oder die eingestellte Länge, was später ist. */
    public double end() {
        double e = length;
        for (Track t : tracks) for (Clip c : t.clips) e = Math.max(e, c.end);
        return e;
    }

    public Show copy() {
        return Project.GSON.fromJson(Project.GSON.toJson(this), Show.class);
    }

    public static Path folder() {
        return de.knirpslarsi.ledstudio.util.AppPaths.dataDir().resolve("shows");
    }

    public static Show load(Path file) throws IOException {
        Show s = Project.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Show.class);
        if (s == null) throw new IOException("Leere Datei");
        if (s.tracks == null) s.tracks = new ArrayList<>();
        for (Track t : s.tracks) {
            if (t.targets == null) t.targets = new ArrayList<>();
            if (t.clips == null) t.clips = new ArrayList<>();
            for (Clip c : t.clips) {
                if (c.look == null) c.look = Project.Layer.solid("#A020FF");
                if (c.targets == null) c.targets = new ArrayList<>();
            }
        }
        if (s.audio == null) s.audio = "";
        if (s.markers == null) s.markers = new ArrayList<>();
        return s;
    }

    /** Atomar speichern. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Project.GSON.toJson(this), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
