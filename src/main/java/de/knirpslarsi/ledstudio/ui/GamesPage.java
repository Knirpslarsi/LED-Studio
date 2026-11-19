package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.games.Cs2Setup;
import de.knirpslarsi.ledstudio.model.Games;
import de.knirpslarsi.ledstudio.model.Project;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Startanimation der Software, Spielerkennung mit Spielstart-Animation und Counter-Strike 2. */
final class GamesPage extends ScrollPane {
    private static final String NO_PRESET = "— Vorlage nicht wechseln —";

    private final AppCore core;
    private final VBox box = new VBox(12);
    /** Aufgeklappte Karten (Spiel-ID oder Ereignis), bleiben beim Neuaufbau offen. */
    private final Set<String> open = new HashSet<>();
    private final Label running = muted("");
    private final Label cs2Status = muted("");

    GamesPage(AppCore core) {
        this.core = core;
        box.getStyleClass().add("page-narrow");
        box.setMaxWidth(720);
        setContent(box);
        setFitToWidth(true);
        build();
        core.onSettings(this::refreshStatus);
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshStatus()));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
    }

    private Games games() {
        return core.project.games;
    }

    private void save() {
        core.settingsChanged();
    }

    private void build() {
        box.getChildren().setAll(
                Ui.label("Spiele", "page-title"),
                muted("LED Studio erkennt, wenn ein Spiel startet, spielt dazu eine Animation und kann für die Dauer des Spiels "
                        + "eine eigene Vorlage zeigen. Counter-Strike 2 steuert die LEDs sogar direkt: Bombe, Kills, Treffer und mehr."));
        appIntro();
        detection();
        cs2();
        refreshStatus();
    }

    // ---- Startanimation der Software

    private void appIntro() {
        Games.Animation a = games().appIntro;
        CheckBox on = new CheckBox("Beim Start der Software abspielen");
        on.setSelected(a.enabled);
        on.selectedProperty().addListener((o, x, y) -> {
            a.enabled = y;
            save();
        });
        box.getChildren().addAll(Ui.section("Startanimation der Software"),
                muted("Läuft einmal, sobald nach dem Start die LEDs erreichbar sind."),
                card("app", on, null, () -> core.games.preview(a), () -> new AnimationEditor(a, this::save),
                        Ui.button("Standard", "Die mitgelieferte Startanimation wiederherstellen", () -> {
                            games().appIntro = Games.appIntroDefault();
                            save();
                            build();
                        })));
    }

    // ---- Spielerkennung

    private void detection() {
        CheckBox detect = new CheckBox("Spielstart erkennen");
        detect.setSelected(games().detect);
        detect.selectedProperty().addListener((o, x, y) -> {
            games().detect = y;
            save();
        });
        box.getChildren().addAll(Ui.section("Spielstart"), detect, running,
                muted("Ein Spiel wird an seiner .exe erkannt. Ist ein Spiel nicht dabei, füge es mit seiner .exe hinzu."));
        for (Games.Game g : games().games) box.getChildren().add(gameCard(g));
        box.getChildren().add(Ui.row(Ui.button("+ Spiel hinzufügen", this::addGame)));
    }

    private Node gameCard(Games.Game g) {
        CheckBox on = new CheckBox(g.name);
        on.setSelected(g.enabled);
        on.selectedProperty().addListener((o, x, y) -> {
            g.enabled = y;
            save();
        });
        return card(g.id, on, null, () -> core.games.preview(g.intro), () -> gameDetails(g));
    }

    private Node gameDetails(Games.Game g) {
        TextField name = new TextField(g.name);
        name.textProperty().addListener((o, x, y) -> {
            g.name = y.trim();
            save();
        });
        TextField exe = new TextField(g.exe);
        exe.textProperty().addListener((o, x, y) -> {
            g.exe = y.trim();
            save();
        });
        Button pick = Ui.button("Wählen …", () -> {
            File f = chooseExe();
            if (f != null) exe.setText(f.getName());
        });
        CheckBox intro = new CheckBox("Animation beim Spielstart");
        intro.setSelected(g.intro.enabled);
        intro.selectedProperty().addListener((o, x, y) -> {
            g.intro.enabled = y;
            save();
        });
        List<String> names = new ArrayList<>();
        names.add(NO_PRESET);
        for (Project.Preset p : core.project.presets) names.add(p.name);
        ComboBox<String> preset = new ComboBox<>(FXCollections.observableArrayList(names));
        preset.setMaxWidth(Double.MAX_VALUE);
        preset.setValue(g.preset == null || g.preset.isBlank() ? NO_PRESET : g.preset);
        preset.setOnAction(e -> {
            String v = preset.getValue();
            g.preset = v == null || NO_PRESET.equals(v) ? "" : v;
            save();
        });
        VBox v = new VBox(8,
                Ui.field("Name", name),
                Ui.field("Programm (.exe)", Ui.row(grow(exe), pick)),
                Ui.field("Vorlage, solange das Spiel läuft", preset),
                muted("Nach dem Spiel kommt die vorherige Vorlage zurück."),
                intro,
                new AnimationEditor(g.intro, this::save),
                Ui.row(Ui.button("Spiel entfernen", () -> {
                    if (!Ui.confirm(getScene().getWindow(), "Spiel entfernen", "„" + g.name + "“ aus der Liste entfernen?")) return;
                    games().games.remove(g);
                    save();
                    build();
                })));
        return v;
    }

    private File chooseExe() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Spiel auswählen");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Programme", "*.exe"));
        return fc.showOpenDialog(getScene().getWindow());
    }

    private void addGame() {
        File f = chooseExe();
        if (f == null) return;
        Games.Game g = new Games.Game();
        String n = f.getName();
        g.name = n.toLowerCase().endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
        g.exe = n;
        g.intro = Games.gameIntro(0.77);
        games().games.add(g);
        open.add(g.id);
        save();
        build();
    }

    // ---- Counter-Strike 2

    private void cs2() {
        Games.Cs2 c = games().cs2;
        CheckBox on = new CheckBox("Counter-Strike 2 steuert die LEDs");
        on.setSelected(c.enabled);
        on.selectedProperty().addListener((o, x, y) -> {
            c.enabled = y;
            save();
        });
        Spinner<Integer> port = new Spinner<>(1024, 65535, c.port);
        port.setEditable(true);
        port.setPrefWidth(110);
        port.valueProperty().addListener((o, x, y) -> {
            c.port = y;
            save();
        });
        box.getChildren().addAll(Ui.section("Counter-Strike 2"), on, cs2Status,
                muted("Nutzt die offizielle „Game State Integration“ von Valve: CS2 schickt seinen Zustand an LED Studio. "
                        + "Das ist erlaubt und hat nichts mit Cheats zu tun. Einmal einrichten, danach CS2 neu starten."),
                Ui.row(Ui.primary("In CS2 einrichten", this::installCs2), Ui.grow(), Ui.label("Port", "field-label"), port),
                Ui.label("Ereignisse", "field-label"));
        for (Games.Cs2Event e : Games.CS2_EVENTS) box.getChildren().add(eventCard(e));
        box.getChildren().add(Ui.row(Ui.button("Alle Ereignisse auf Standard", () -> {
            if (!Ui.confirm(getScene().getWindow(), "Ereignisse zurücksetzen", "Alle CS2-Animationen auf die mitgelieferten zurücksetzen?")) return;
            c.events = Games.cs2Defaults();
            save();
            build();
        })));
    }

    private Node eventCard(Games.Cs2Event e) {
        Games.Animation a = games().cs2.events.get(e.id());
        CheckBox on = new CheckBox(e.title());
        on.setSelected(a.enabled);
        on.selectedProperty().addListener((o, x, y) -> {
            a.enabled = y;
            save();
        });
        Label help = e.help().isBlank() ? null : muted(e.help());
        return card("cs2:" + e.id(), on, help, () -> testEvent(e, a), () -> new AnimationEditor(a, this::save));
    }

    private void testEvent(Games.Cs2Event e, Games.Animation a) {
        // Wie im Spiel, mit Blende und Feuer auf voller Stärke; gehaltene enden nach 3 Sekunden.
        core.games.preview(a);
    }

    private void installCs2() {
        Games.Cs2 c = games().cs2;
        core.background(() -> {
            List<Path> written = Cs2Setup.install(c.port, c.token);
            javafx.application.Platform.runLater(() -> {
                if (written.isEmpty()) {
                    Ui.error(getScene().getWindow(), "CS2 nicht gefunden",
                            "Counter-Strike 2 wurde in keiner Steam-Bibliothek gefunden. Ist es über Steam installiert?");
                } else {
                    StringBuilder b = new StringBuilder("Fertig. Starte CS2 neu, dann reagieren die LEDs aufs Spiel.\n\n");
                    written.forEach(p -> b.append(p).append('\n'));
                    Ui.info(getScene().getWindow(), "CS2 eingerichtet", b.toString());
                }
                refreshStatus();
            });
        }, null, err -> Ui.error(getScene().getWindow(), "Einrichten fehlgeschlagen", err));
    }

    /** Für Screenshots: Startanimation und ein CS2-Ereignis aufgeklappt. */
    void demo() {
        open.add("cs2:bomb_planted");
        build();
        javafx.application.Platform.runLater(() -> {
            layout();
            setVvalue(0.62);
        });
    }

    // ---- Anzeige

    private void refreshStatus() {
        List<String> names = core.games.runningNames();
        running.setText(names.isEmpty() ? "Gerade läuft kein bekanntes Spiel." : "Läuft gerade: " + String.join(", ", names));
        Games.Cs2 c = games().cs2;
        String text;
        if (!c.enabled) text = "Aus.";
        else if (!core.games.serverError.isBlank()) text = core.games.serverError;
        else {
            long age = System.currentTimeMillis() - core.games.cs2LastPacket;
            if (core.games.cs2LastPacket > 0 && age < 15_000) {
                text = "Verbunden mit CS2." + (core.games.cs2LastEvent.isBlank() ? "" : " Zuletzt: " + core.games.cs2LastEvent);
            } else {
                text = "Wartet auf CS2 (Port " + c.port + "). Noch nicht eingerichtet? Klick auf „In CS2 einrichten“.";
            }
        }
        cs2Status.setText(text);
    }

    /** Eine aufklappbare Karte: Schalter, Hinweis, Abspielen, Einstellungen. */
    private Node card(String key, CheckBox toggle, Label note, Runnable play, java.util.function.Supplier<Node> details, Node... extra) {
        VBox card = new VBox(8);
        card.getStyleClass().add("layer-row");
        card.setStyle("-fx-padding: 10;");
        Button test = Ui.button("▶ Abspielen", "Jetzt auf den LEDs zeigen", play);
        Button edit = Ui.button(open.contains(key) ? "Schließen" : "Anpassen", () -> {
            if (!open.remove(key)) open.add(key);
            build();
        });
        List<Node> row = new ArrayList<>(List.of(toggle, Ui.grow()));
        row.addAll(List.of(extra));
        row.add(test);
        row.add(edit);
        card.getChildren().add(Ui.row(row.toArray(Node[]::new)));
        if (note != null) card.getChildren().add(note);
        if (open.contains(key)) card.getChildren().add(details.get());
        return card;
    }

    private static Node grow(TextField f) {
        javafx.scene.layout.HBox.setHgrow(f, javafx.scene.layout.Priority.ALWAYS);
        return f;
    }

    private static Label muted(String text) {
        return AnimationEditor.muted(text);
    }
}
