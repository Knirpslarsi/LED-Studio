package de.knirpslarsi.ledstudio.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Startanimation der Software, Spielerkennung mit Spielstart-Animation und die Anbindung an Counter-Strike 2. */
public class Games {
    /** Eine kurze Animation, die über allem liegt. seconds 0 = hält, bis das Spiel sie beendet. */
    public static class Animation {
        public boolean enabled = true;
        public Project.Layer look = new Project.Layer();
        public double seconds = 3, fadeIn = 0.2, fadeOut = 0.8;

        public Animation() {
        }

        Animation(Project.Layer look, double seconds, double fadeIn, double fadeOut) {
            this.look = look;
            this.seconds = seconds;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
        }

        Animation off() {
            enabled = false;
            return this;
        }

        public Animation copy() {
            return Project.GSON.fromJson(Project.GSON.toJson(this), Animation.class);
        }
    }

    /** Ein Spiel, erkannt am Namen seiner .exe. */
    public static class Game {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String exe = "";
        public boolean enabled = true;
        /** Animation beim Spielstart. */
        public Animation intro;
        /** Vorlage, solange das Spiel läuft; leer = nicht wechseln. */
        public String preset = "";

        public Game() {
        }

        Game(String name, String exe, double hue) {
            this.name = name;
            this.exe = exe;
            this.intro = gameIntro(hue);
        }
    }

    /** Ein Ereignis aus Counter-Strike 2. */
    public record Cs2Event(String id, String title, String help) {
    }

    public static final List<Cs2Event> CS2_EVENTS = List.of(
            new Cs2Event("match_start", "Match beginnt", "Aufwärmen vorbei, das Match läuft."),
            new Cs2Event("round_start", "Runde beginnt", "Kaufzeit vorbei, es geht los."),
            new Cs2Event("round_won", "Runde gewonnen", "Dein Team gewinnt die Runde."),
            new Cs2Event("round_lost", "Runde verloren", "Das andere Team gewinnt die Runde."),
            new Cs2Event("bomb_planted", "Bombe gelegt", "Läuft, bis sie explodiert oder entschärft ist; wird immer schneller."),
            new Cs2Event("bomb_exploded", "Bombe explodiert", ""),
            new Cs2Event("bomb_defused", "Bombe entschärft", ""),
            new Cs2Event("kill", "Kill", "Du hast einen Gegner ausgeschaltet."),
            new Cs2Event("headshot", "Kill per Kopfschuss", "Statt „Kill“, wenn es ein Kopfschuss war."),
            new Cs2Event("death", "Du bist gestorben", ""),
            new Cs2Event("low_health", "Wenig Leben", "Solange du unter 30 Leben hast."),
            new Cs2Event("flashed", "Geblendet", "Folgt der Stärke der Blendgranate."),
            new Cs2Event("burning", "Brennen", "Solange du im Feuer stehst."),
            new Cs2Event("team_ct", "Teamfarbe CT (dauerhaft)", "Grundlicht, solange du CT bist. Verdeckt die Vorlage."),
            new Cs2Event("team_t", "Teamfarbe T (dauerhaft)", "Grundlicht, solange du T bist. Verdeckt die Vorlage."));

    public static class Cs2 {
        public boolean enabled = true;
        public int port = 47800;
        /** Schutz, damit nur CS2 Daten schicken kann. Steht auch in der Konfigurationsdatei im Spiel. */
        public String token = UUID.randomUUID().toString().replace("-", "");
        public Map<String, Animation> events = new LinkedHashMap<>();
    }

    /** Startanimation, wenn die Software startet und die LEDs erreichbar sind. */
    public Animation appIntro = appIntroDefault();
    /** Laufende Spiele erkennen. */
    public boolean detect = true;
    public List<Game> games = defaultGames();
    public Cs2 cs2 = new Cs2();

    /** Ergänzt fehlende Teile, z. B. nach einem Update mit neuen Ereignissen. */
    public void complete() {
        if (appIntro == null || appIntro.look == null) appIntro = appIntroDefault();
        if (games == null) games = defaultGames();
        for (Game g : games) if (g.intro == null || g.intro.look == null) g.intro = gameIntro(0.77);
        if (cs2 == null) cs2 = new Cs2();
        if (cs2.events == null) cs2.events = new LinkedHashMap<>();
        if (cs2.token == null || cs2.token.isBlank()) cs2.token = new Cs2().token;
        Map<String, Animation> defaults = cs2Defaults();
        for (Cs2Event e : CS2_EVENTS) {
            Animation a = cs2.events.get(e.id());
            if (a == null || a.look == null) cs2.events.put(e.id(), defaults.get(e.id()));
        }
    }

    public Game game(String id) {
        for (Game g : games) if (g.id.equals(id)) return g;
        return null;
    }

    // ---- Vorgaben

    private static Project.Layer layer(String effect, Object... kv) {
        Project.Layer l = new Project.Layer();
        l.effect = effect;
        l.params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) l.params.put((String) kv[i], kv[i + 1]);
        return l;
    }

    private static Project.Layer color(String hex) {
        return Project.Layer.solid(hex);
    }

    public static Animation appIntroDefault() {
        Project.Layer l = layer("rim_chase", "rainbow", 1, "mode", "led", "speed", 45.0, "tail", 28.0, "gap", 0.0, "soft", 2.0);
        return new Animation(l, 3.5, 0.2, 1.2);
    }

    public static Animation gameIntro(double hue) {
        Project.Layer l = layer("rim_chase", "hue", hue, "rainbow", 0, "mode", "led", "speed", 60.0, "tail", 30.0, "gap", 0.0, "soft", 2.0);
        return new Animation(l, 3, 0.15, 1.0);
    }

    private static List<Game> defaultGames() {
        List<Game> list = new ArrayList<>();
        list.add(new Game("Counter-Strike 2", "cs2.exe", 0.08));
        list.add(new Game("Valorant", "VALORANT-Win64-Shipping.exe", 0.98));
        list.add(new Game("Fortnite", "FortniteClient-Win64-Shipping.exe", 0.72));
        list.add(new Game("Rocket League", "RocketLeague.exe", 0.58));
        list.add(new Game("League of Legends", "League of Legends.exe", 0.13));
        list.add(new Game("Apex Legends", "r5apex.exe", 0.0));
        list.add(new Game("Dota 2", "dota2.exe", 0.0));
        list.add(new Game("GTA V", "GTA5.exe", 0.33));
        list.add(new Game("Euro Truck Simulator 2", "eurotrucks2.exe", 0.1));
        list.add(new Game("Roblox", "RobloxPlayerBeta.exe", 0.55));
        list.add(new Game("Minecraft (Launcher)", "Minecraft.exe", 0.3));
        return list;
    }

    public static Map<String, Animation> cs2Defaults() {
        Map<String, Animation> m = new LinkedHashMap<>();
        m.put("match_start", new Animation(layer("rainbow", "speed", 1.5, "repeat", 1.0), 3, 0.2, 1.0));
        m.put("round_start", new Animation(color("#FFFFFF"), 0.5, 0.05, 0.45));
        m.put("round_won", new Animation(layer("breathing", "hue", 0.33, "speed", 1.5, "min", 0.15), 3, 0.1, 1.0));
        m.put("round_lost", new Animation(layer("breathing", "hue", 0.0, "speed", 0.6, "min", 0.05), 3, 0.1, 1.0));
        Project.Layer bomb = layer("breathing", "hue", 0.0, "speed", 1.0, "min", 0.05);
        bomb.speedMode = "up";
        bomb.speedMin = 1.0;
        bomb.speedMax = 6.0;
        m.put("bomb_planted", new Animation(bomb, 40, 0.1, 0.3));
        Project.Layer boom = layer("fire", "speed", 2.5, "scale", 3.0);
        boom.brightness = 1.4;
        m.put("bomb_exploded", new Animation(boom, 3, 0.0, 1.5));
        m.put("bomb_defused", new Animation(color("#2080FF"), 2.5, 0.05, 1.5));
        m.put("kill", new Animation(color("#FFFFFF"), 0.3, 0.02, 0.25));
        m.put("headshot", new Animation(color("#FFD000"), 0.5, 0.02, 0.4));
        m.put("death", new Animation(color("#FF0000"), 2, 0.02, 1.8));
        m.put("low_health", new Animation(layer("breathing", "hue", 0.0, "speed", 1.4, "min", 0.2), 0, 0.3, 0.5));
        m.put("flashed", new Animation(color("#FFFFFF"), 0, 0.0, 0.3));
        m.put("burning", new Animation(layer("fire", "speed", 2.0, "scale", 3.0), 0, 0.1, 0.5));
        m.put("team_ct", new Animation(color("#3060D0"), 0, 0.5, 0.5).off());
        m.put("team_t", new Animation(color("#E08A20"), 0, 0.5, 0.5).off());
        return m;
    }
}
