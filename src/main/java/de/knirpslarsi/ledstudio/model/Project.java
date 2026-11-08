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

    /**
     * Eine Vorlage besteht aus Ebenen. Ältere Vorlagen haben nur effect + params; sie werden beim ersten
     * Zugriff zu einer Ebene, die alles abdeckt.
     */
    public static class Preset {
        public String name;
        public String effect;
        public Map<String, Object> params = new LinkedHashMap<>();
        public List<Layer> layers;

        public Preset() {
        }

        public Preset(String name, String effect, Map<String, Object> params) {
            this.name = name;
            this.effect = effect;
            this.params = new LinkedHashMap<>(params);
        }

        public List<Layer> layers() {
            if (layers == null) {
                layers = new ArrayList<>();
                if (effect != null) {
                    Layer l = new Layer();
                    l.effect = effect;
                    l.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
                    layers.add(l);
                }
                effect = null;
                params = null;
            }
            return layers;
        }

        /** Tiefe Kopie mit neuen Ebenen-IDs. */
        public Preset copy(String newName) {
            layers();
            Preset c = GSON.fromJson(GSON.toJson(this), Preset.class);
            c.name = newName;
            c.layers.forEach(l -> l.id = java.util.UUID.randomUUID().toString());
            return c;
        }
    }

    /** Eine Ebene: ein Effekt auf einem frei wählbaren Ziel, mit eigenem Feinschliff. Obere Ebenen liegen über unteren. */
    public static class Layer {
        public String id = java.util.UUID.randomUUID().toString();
        public String name = "";
        public boolean enabled = true;
        public String effect = "rainbow";
        public Map<String, Object> params = new LinkedHashMap<>();
        /** Leer = alles. Sonst "device:<key>", "zone:<key>#<zone>", "fixture:<id>" oder "none". */
        public List<String> targets = new ArrayList<>();
        /** "own": Effekt füllt genau sein Ziel. "global": Effekt läuft über den ganzen Aufbau, nur das Ziel zeigt ihn. */
        public String scope = "own";
        public String blend = "normal";
        public double opacity = 1, brightness = 1, saturation = 1, hueShift = 0, gamma = 1, speed = 1, phase = 0, trail = 0;
        public double rotate = 0, scale = 1;
        public boolean mirrorX, mirrorY, reverse;
        public String ledFilter = "all";
        public String colorMode = "off";
        public String color1 = "#A020FF", color2 = "#00DDFF", color3 = "#FF2080";
        public boolean useColor3;
        public String audioBand = "off";
        public double audioAmount = 1;

        public Layer copy() {
            Layer c = GSON.fromJson(GSON.toJson(this), Layer.class);
            c.id = java.util.UUID.randomUUID().toString();
            return c;
        }
    }

    public static class Audio {
        public boolean enabled;
        public String device = "default";
        public String mode = "Beat-Balken";
        public double gain = 4.0, smoothing = 0.75, floor = 0.02, minimum = 0.0, duration = 0.8, pause = 0.1, length = 1.2;
        public String band = "Bass";
        public String palette = "Lila";
        /** Jeder Beat startet einen eigenen Lauf, statt während eines Laufs ignoriert zu werden. */
        public boolean overlap = true;
        public double sensitivity = 1.0;
        /** "music" oder "bpm" (fester Takt). */
        public String trigger = "music";
        public double bpm = 120;
        /** Ursprung der Ringe: "center" oder "fixture". */
        public String origin = "center";
    }

    public String current = "Lila Balken";
    public double speed = 1.0, brightness = 1.0, trail = 0.0;
    /** Übergangszeit jeder LED in Millisekunden. */
    public double smoothMs = 70;
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
