package de.knirpslarsi.ledstudio.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.knirpslarsi.ledstudio.util.AppPaths;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Alles, was gespeichert wird: Einstellungen, Vorlagen und das Layout. */
public class Project {
    private static final Logger LOG = Logger.getLogger("Project");
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String OFF = "off";

    public static class Preset {
        public String name;
        public String effect;
        public Map<String, Object> params = new LinkedHashMap<>();

        public Preset() {
        }

        public Preset(String name, String effect, Map<String, Object> params) {
            this.name = name;
            this.effect = effect;
            this.params = new LinkedHashMap<>(params);
        }
    }

    public static class Audio {
        public boolean enabled;
        public String device = "default";
        public String mode = "Beat-Balken";
        public double gain = 4.0, smoothing = 0.75, floor = 0.02, minimum = 0.0, duration = 0.8, pause = 0.1, length = 1.2;
        public String band = "Bass";
        public String palette = "Lila";
    }

    public String current = "Lila Balken";
    public double speed = 1.0, brightness = 1.0, trail = 0.0;
    public String color = "preset";
    public int fps = 40;
    public String host = "127.0.0.1";
    public int port = 6742;
    public String openrgbPath = "";
    public boolean startOpenrgb = true;
    public int startDelay = 8;
    public boolean closeToTray;
    public boolean autoUpdate = true;
    public List<Preset> presets = new ArrayList<>();
    public Audio audio = new Audio();
    public List<Fixture> fixtures = new ArrayList<>();

    public Preset preset(String name) {
        for (Preset p : presets) if (p.name.equals(name)) return p;
        return null;
    }

    public String uniquePresetName(String base) {
        String candidate = base;
        int i = 2;
        while (preset(candidate) != null || candidate.equals(OFF)) candidate = base + " (" + i++ + ")";
        return candidate;
    }

    public static List<Preset> defaultPresets() {
        try (Reader r = new InputStreamReader(Project.class.getResourceAsStream("/ledstudio/default-presets.json"), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<Preset>>() {
            }.getType();
            return GSON.fromJson(r, type);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Project load() {
        Path file = AppPaths.projectFile();
        Project p = null;
        if (Files.exists(file)) {
            try {
                p = GSON.fromJson(Files.readString(file), Project.class);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "project.json nicht lesbar, wird gesichert und neu angelegt", e);
                try {
                    Files.move(file, file.resolveSibling("project.broken.json"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        if (p == null) p = new Project();
        if (p.presets == null || p.presets.isEmpty()) p.presets = defaultPresets();
        if (p.audio == null) p.audio = new Audio();
        if (p.fixtures == null) p.fixtures = new ArrayList<>();
        return p;
    }

    /** Atomar speichern, damit ein Absturz die Datei nie halb geschrieben zurücklässt. */
    public synchronized void save() {
        Path file = AppPaths.projectFile();
        try {
            Path tmp = file.resolveSibling("project.json.tmp");
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Speichern fehlgeschlagen", e);
        }
    }

    public String fixturesJson() {
        return GSON.toJson(fixtures);
    }

    public void restoreFixtures(String json) {
        Type type = new TypeToken<List<Fixture>>() {
        }.getType();
        fixtures = GSON.fromJson(json, type);
    }
}
