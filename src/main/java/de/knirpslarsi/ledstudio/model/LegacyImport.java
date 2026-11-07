package de.knirpslarsi.ledstudio.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Übernimmt Vorlagen und Einstellungen aus der config.json der alten Python-Version. */
public final class LegacyImport {
    private LegacyImport() {
    }

    /** Gibt die Zahl übernommener Vorlagen zurück. Gleichnamige Vorlagen werden ersetzt. */
    public static int apply(Project project, Path configJson) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(configJson)).getAsJsonObject();
        if (!o.has("presets") || !o.get("presets").isJsonArray()) throw new IOException("Keine Vorlagen in dieser Datei gefunden.");
        int count = 0;
        for (JsonElement e : o.getAsJsonArray("presets")) {
            JsonObject p = e.getAsJsonObject();
            String name = p.get("name").getAsString();
            Map<String, Object> params = new LinkedHashMap<>();
            if (p.has("params")) {
                p.getAsJsonObject("params").entrySet().forEach(x -> params.put(x.getKey(), value(x.getValue())));
            }
            Project.Preset imported = new Project.Preset(name, p.get("effect").getAsString(), params);
            Project.Preset existing = project.preset(name);
            if (existing != null) project.presets.set(project.presets.indexOf(existing), imported);
            else project.presets.add(imported);
            count++;
        }
        if (o.has("current")) {
            String current = o.get("current").getAsString();
            if (project.preset(current) != null || Project.OFF.equals(current)) project.current = current;
        }
        if (o.has("speed")) project.speed = o.get("speed").getAsDouble();
        if (o.has("brightness")) project.brightness = o.get("brightness").getAsDouble();
        if (o.has("fps")) project.fps = o.get("fps").getAsInt();
        if (o.has("color")) project.color = o.get("color").getAsString();
        if (o.has("host")) project.host = o.get("host").getAsString();
        if (o.has("port")) project.port = o.get("port").getAsInt();
        if (o.has("openrgb_path")) project.openrgbPath = o.get("openrgb_path").getAsString();
        if (o.has("start_openrgb")) project.startOpenrgb = o.get("start_openrgb").getAsBoolean();
        if (o.has("start_delay")) project.startDelay = o.get("start_delay").getAsInt();
        if (o.has("audio")) {
            Project.Audio imported = Project.GSON.fromJson(o.get("audio"), Project.Audio.class);
            if (imported != null) project.audio = imported;
        }
        return count;
    }

    private static Object value(JsonElement v) {
        if (v.isJsonPrimitive()) {
            var p = v.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        return v.toString();
    }
}
