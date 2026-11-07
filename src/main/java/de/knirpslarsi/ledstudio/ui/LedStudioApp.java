package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.Main;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.update.Updater;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Hauptfenster. */
public final class LedStudioApp extends Application {
    private static final Logger LOG = Logger.getLogger("App");
    private AppCore core;
    private Stage stage;
    private Tray tray;
    private boolean quitting;
    private boolean trayAvailable;
    private final Map<String, Node> pages = new LinkedHashMap<>();
    private final Map<String, ToggleButton> navButtons = new LinkedHashMap<>();
    private final StackPane content = new StackPane();
    private EffectsPage effectsPage;
    private final Label oldVersionWarning = Ui.label("Das alte LED Studio läuft noch (Tray). Beende es, sonst steuern beide die LEDs und es flackert.", "warning");

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        List<String> args = getParameters().getRaw();
        Optional<String> screenshots = args.stream().filter(a -> a.startsWith("--screenshots=")).map(a -> a.substring(14)).findFirst();
        boolean real = args.contains("--read-only");
        boolean demo = !real && (args.contains("--demo") || screenshots.isPresent());
        boolean autostart = args.contains("--autostart");

        Platform.setImplicitExit(false);
        core = new AppCore(demo, autostart, real);

        UpdateController updates = new UpdateController(core, stage::isShowing, this::quit);
        effectsPage = new EffectsPage(core, () -> showPage("Layout"));
        pages.put("Effekte", effectsPage);
        pages.put("Layout", new LayoutPage(core));
        pages.put("Musik", new MusicPage(core));
        pages.put("Einstellungen", new SettingsPage(core, updates, () -> effectsPage.externalChange()));
        pages.values().forEach(p -> {
            p.setVisible(false);
            content.getChildren().add(p);
        });

        BorderPane root = new BorderPane(content);
        root.getStyleClass().add("root-pane");
        root.setLeft(nav());
        root.setBottom(globalBar());
        root.setTop(banner(updates));

        Scene scene = new Scene(root, 1360, 860);
        scene.getStylesheets().add(getClass().getResource("/ledstudio/app.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("LED Studio " + Updater.currentVersion() + (demo ? " – Demo" : ""));
        stage.setMinWidth(1050);
        stage.setMinHeight(640);
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/ledstudio/icon.png")));
        stage.setOnCloseRequest(e -> {
            if (core.project.closeToTray && trayAvailable) {
                e.consume();
                stage.hide();
            } else {
                e.consume();
                quit();
            }
        });

        showPage(core.project.fixtures.isEmpty() ? "Layout" : "Effekte");
        LOG.info("LED Studio " + Updater.currentVersion() + " gestartet");
        core.start();

        if (screenshots.isPresent()) {
            stage.show();
            runScreenshots(screenshots.get());
            return;
        }
        tray = new Tray(core, this::showWindow, this::quit);
        trayAvailable = tray.start();
        Main.listenForSecondInstance(this::showWindow);
        if (!autostart || !trayAvailable) stage.show();
        updates.start();
        if (!demo) warnIfOldVersionRuns();
    }

    /** Die alte Python-Version belegt ihren festen Port. Beide zusammen würden die LEDs flackern lassen. */
    private void warnIfOldVersionRuns() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 47651), 200);
        } catch (IOException e) {
            return;
        }
        oldVersionWarning.setVisible(true);
        oldVersionWarning.setManaged(true);
    }

    private void showWindow() {
        stage.show();
        stage.setIconified(false);
        stage.toFront();
    }

    private VBox nav() {
        Label logo = Ui.label("LED Studio", "logo");
        ToggleGroup group = new ToggleGroup();
        VBox box = new VBox(4, logo);
        for (String name : pages.keySet()) {
            ToggleButton b = new ToggleButton(name);
            b.getStyleClass().add("nav-button");
            b.setToggleGroup(group);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> showPage(name));
            navButtons.put(name, b);
            box.getChildren().add(b);
        }
        Label status = Ui.label(core.connection.status(), "muted");
        status.setWrapText(true);
        status.setMaxWidth(150);
        core.onStatus(status::setText);
        oldVersionWarning.setWrapText(true);
        oldVersionWarning.setMaxWidth(150);
        oldVersionWarning.setVisible(false);
        oldVersionWarning.setManaged(false);
        box.getChildren().addAll(Ui.grow(), oldVersionWarning, status);
        box.getStyleClass().add("nav");
        return box;
    }

    private void showPage(String name) {
        pages.forEach((k, p) -> p.setVisible(k.equals(name)));
        ToggleButton b = navButtons.get(name);
        if (b != null) b.setSelected(true);
    }

    private HBox banner(UpdateController updates) {
        Label text = new Label();
        text.textProperty().bind(updates.bannerText);
        Button now = Ui.primary("Jetzt aktualisieren", updates::install);
        now.setVisible(Updater.canInstall());
        now.setManaged(Updater.canInstall());
        Button later = Ui.button("Später", () -> updates.bannerVisible.set(false));
        HBox box = Ui.row(text, Ui.grow(), now, later);
        box.getStyleClass().add("banner");
        box.visibleProperty().bind(updates.bannerVisible);
        box.managedProperty().bind(updates.bannerVisible);
        return box;
    }

    private HBox globalBar() {
        Project p = core.project;
        ToggleButton power = new ToggleButton("An");
        power.getStyleClass().add("power");
        power.setSelected(!Project.OFF.equals(p.current));
        String[] lastOn = {Project.OFF.equals(p.current) ? (p.presets.isEmpty() ? Project.OFF : p.presets.get(0).name) : p.current};
        power.setOnAction(e -> {
            if (power.isSelected()) core.selectPreset(lastOn[0]);
            else {
                if (!Project.OFF.equals(p.current)) lastOn[0] = p.current;
                core.selectPreset(Project.OFF);
            }
            effectsPage.externalChange();
        });
        core.onSettings(() -> {
            power.setSelected(!Project.OFF.equals(p.current));
            power.setText(power.isSelected() ? "An" : "Aus");
            if (!Project.OFF.equals(p.current)) lastOn[0] = p.current;
        });
        power.setText(power.isSelected() ? "An" : "Aus");

        VBox brightness = Ui.slider("Helligkeit", 0, 1, p.brightness, 0.01, v -> {
            p.brightness = v;
            core.settingsChanged();
        });
        VBox speed = Ui.slider("Tempo", 0.1, 4, p.speed, 0.05, v -> {
            p.speed = v;
            core.settingsChanged();
        });
        VBox trail = Ui.slider("Nachleuchten", 0, 1, p.trail, 0.01, v -> {
            p.trail = v;
            core.settingsChanged();
        });
        for (VBox v : new VBox[]{brightness, speed, trail}) {
            v.setPrefWidth(190);
            HBox.setHgrow(v, Priority.SOMETIMES);
        }
        ComboBox<String> color = new ComboBox<>(FXCollections.observableArrayList(Engine.GLOBAL_COLORS));
        color.setConverter(new StringConverter<>() {
            @Override
            public String toString(String s) {
                return "preset".equals(s) ? "Wie Vorlage" : s;
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        color.setValue(p.color);
        color.setOnAction(e -> {
            p.color = color.getValue();
            core.settingsChanged();
        });
        Spinner<Integer> fps = new Spinner<>(5, 120, p.fps, 5);
        fps.setEditable(true);
        fps.setPrefWidth(84);
        fps.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                p.fps = b;
                core.settingsChanged();
            }
        });
        HBox bar = new HBox(16, power, brightness, speed, trail, Ui.field("Farbe", color), Ui.field("Bilder/s", fps));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("global-bar");
        return bar;
    }

    void quit() {
        if (quitting) return;
        quitting = true;
        stage.hide();
        if (tray != null) tray.stop();
        Thread t = new Thread(() -> {
            try {
                core.shutdown(); // schaltet die LEDs aus
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Beenden", e);
            }
            Platform.exit();
            System.exit(0);
        }, "shutdown");
        t.start();
    }

    /** Entwicklerhilfe: speichert jede Seite als PNG und beendet das Programm. */
    private void runScreenshots(String dir) {
        PauseTransition wait = new PauseTransition(Duration.seconds(2.5));
        wait.setOnFinished(e -> {
            if (core.project.fixtures.isEmpty() && core.demo) {
                core.project.fixtures.addAll(LayoutTools.legacyNzxtChain(core.controllers()));
                core.layoutChanged();
            }
            if (core.demo) {
                demoScene();
                effectsPage.externalChange();
                effectsPage.showTab(1);
            }
            if (pages.get("Layout") instanceof LayoutPage page) page.selectFirst();
            java.util.List<String> names = new java.util.ArrayList<>(pages.keySet());
            names.add("Feinschliff");
            shoot(dir, names.toArray(new String[0]), 0);
        });
        wait.play();
    }

    /** Für Screenshots: eine Vorlage, bei der jeder Teil des Aufbaus etwas anderes macht. */
    private void demoScene() {
        var fixtures = core.project.fixtures;
        Project.Preset scene = new Project.Preset();
        scene.name = "Jeder eigener Effekt";
        scene.layers = new java.util.ArrayList<>();
        String[][] parts = {{"rotor", "Hue 2 Channel 5"}, {"plasma", "Hue 2 Channel 1"}, {"aurora", "Hue 2 Channel 2"}};
        for (String[] part : parts) {
            Project.Layer l = new Project.Layer();
            l.effect = part[0];
            fixtures.stream().filter(f -> f.zoneName.equals(part[1])).findFirst().ifPresent(
                    f -> l.targets = new java.util.ArrayList<>(java.util.List.of(de.knirpslarsi.ledstudio.layout.Targets.zone(f.deviceKey, part[1]))));
            scene.layers.add(l);
        }
        core.project.presets.add(0, scene);
        core.selectPreset(scene.name);
    }

    private void shoot(String dir, String[] names, int i) {
        if (i >= names.length) {
            quit();
            return;
        }
        if (names[i].equals("Feinschliff")) {
            showPage("Effekte");
            effectsPage.showTab(2);
        } else {
            showPage(names[i]);
        }
        PauseTransition t = new PauseTransition(Duration.seconds(1.5));
        t.setOnFinished(e -> {
            WritableImage img = stage.getScene().snapshot(null);
            try {
                new File(dir).mkdirs();
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(dir, names[i] + ".png"));
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Screenshot", ex);
            }
            shoot(dir, names, i + 1);
        });
        t.play();
    }
}
