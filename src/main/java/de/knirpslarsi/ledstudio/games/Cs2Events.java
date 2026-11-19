package de.knirpslarsi.ledstudio.games;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Macht aus den Zuständen, die Counter-Strike 2 per Game State Integration schickt, Ereignisse: Was hat sich seit dem
 * letzten Paket geändert? Ohne Netzwerk und Oberfläche, damit testbar.
 */
public final class Cs2Events {
    public enum Kind {PLAY, RELEASE, LEVEL}

    /** PLAY startet eine Animation, RELEASE blendet eine gehaltene aus, LEVEL setzt ihre Stärke (0..1). */
    public record Action(Kind kind, String id, double value) {
        static Action play(String id) {
            return new Action(Kind.PLAY, id, 1);
        }

        static Action release(String id) {
            return new Action(Kind.RELEASE, id, 0);
        }

        static Action level(String id, double v) {
            return new Action(Kind.LEVEL, id, v);
        }
    }

    public static final int LOW_HEALTH = 30;

    private String mapPhase, roundPhase, bomb, team;
    private Integer health, kills, headshots, flashed, burning;
    private boolean low, planted;

    /** Wertet ein Paket aus und gibt zurück, was die LEDs tun sollen. */
    public List<Action> update(JsonObject root) {
        List<Action> out = new ArrayList<>();
        JsonObject map = obj(root, "map");
        if (map == null) {
            // Kein Spiel mehr (Hauptmenü): alles Gehaltene beenden.
            leave(out);
            return out;
        }
        String newMap = str(map, "phase");
        if ("live".equals(newMap) && mapPhase != null && !"live".equals(mapPhase)) out.add(Action.play("match_start"));
        mapPhase = newMap;

        JsonObject player = obj(root, "player");
        String me = str(obj(root, "provider"), "steamid");
        boolean own = player != null && me != null && me.equals(str(player, "steamid"));
        String newTeam = own ? str(player, "team") : team;

        JsonObject round = obj(root, "round");
        String newPhase = str(round, "phase");
        if ("live".equals(newPhase) && "freezetime".equals(roundPhase)) out.add(Action.play("round_start"));
        if ("over".equals(newPhase) && !"over".equals(roundPhase) && roundPhase != null) {
            String win = str(round, "win_team");
            if (win != null && newTeam != null) out.add(Action.play(win.equals(newTeam) ? "round_won" : "round_lost"));
        }
        roundPhase = newPhase;

        String newBomb = str(round, "bomb");
        if (newBomb != null && !newBomb.equals(bomb)) {
            switch (newBomb) {
                case "planted" -> {
                    out.add(Action.play("bomb_planted"));
                    planted = true;
                }
                case "exploded" -> {
                    releasePlanted(out);
                    out.add(Action.play("bomb_exploded"));
                }
                case "defused" -> {
                    releasePlanted(out);
                    out.add(Action.play("bomb_defused"));
                }
                default -> {
                }
            }
        }
        if (newBomb == null && planted && !"over".equals(newPhase)) releasePlanted(out);
        if ("freezetime".equals(newPhase)) releasePlanted(out);
        bomb = newBomb;

        if (own) {
            if (newTeam != null && !newTeam.equals(team)) {
                if (team != null) out.add(Action.release(teamEvent(team)));
                out.add(Action.play(teamEvent(newTeam)));
            }
            team = newTeam;
            ownPlayer(obj(player, "state"), obj(player, "match_stats"), out);
        } else {
            // Man schaut jemand anderem zu: eigene Zustände beenden und beim Zurückkommen neu anfangen.
            endPersonal(out);
            health = kills = headshots = flashed = burning = null;
        }
        return out;
    }

    private void ownPlayer(JsonObject state, JsonObject stats, List<Action> out) {
        if (state == null) return;
        Integer hp = num(state, "health");
        if (hp != null) {
            if (health != null && health > 0 && hp == 0) {
                endPersonal(out);
                out.add(Action.play("death"));
            }
            boolean nowLow = hp > 0 && hp < LOW_HEALTH;
            if (nowLow && !low) out.add(Action.play("low_health"));
            if (!nowLow && low) out.add(Action.release("low_health"));
            low = nowLow;
            health = hp;
        }
        Integer k = num(state, "round_kills"), hs = num(state, "round_killhs");
        if (k != null) {
            if (kills != null && k > kills) {
                boolean head = hs != null && headshots != null && hs > headshots;
                out.add(Action.play(head ? "headshot" : "kill"));
            }
            kills = k;
            headshots = hs;
        }
        flashed = strength(state, "flashed", flashed, "flashed", out);
        burning = strength(state, "burning", burning, "burning", out);
    }

    /** Blenden und Brennen kommen als 0..255 und folgen der Stärke. */
    private static Integer strength(JsonObject state, String field, Integer before, String id, List<Action> out) {
        Integer v = num(state, field);
        if (v == null) return before;
        int was = before == null ? 0 : before;
        if (v > 0 && was == 0) out.add(Action.play(id));
        if (v > 0) out.add(Action.level(id, v / 255.0));
        if (v == 0 && was > 0) out.add(Action.release(id));
        return v;
    }

    private void endPersonal(List<Action> out) {
        if (low) out.add(Action.release("low_health"));
        low = false;
        if (flashed != null && flashed > 0) out.add(Action.release("flashed"));
        if (burning != null && burning > 0) out.add(Action.release("burning"));
        flashed = burning = 0;
    }

    private void releasePlanted(List<Action> out) {
        if (planted) out.add(Action.release("bomb_planted"));
        planted = false;
    }

    private void leave(List<Action> out) {
        endPersonal(out);
        releasePlanted(out);
        if (team != null) out.add(Action.release(teamEvent(team)));
        mapPhase = roundPhase = bomb = team = null;
        health = kills = headshots = flashed = burning = null;
    }

    private static String teamEvent(String team) {
        return "CT".equals(team) ? "team_ct" : "team_t";
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Integer num(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
