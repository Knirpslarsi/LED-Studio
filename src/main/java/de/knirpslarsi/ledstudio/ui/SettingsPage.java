package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.effects.ScriptEffects;
import de.knirpslarsi.ledstudio.model.LegacyImport;
import de.knirpslarsi.ledstudio.update.Updater;
import de.knirpslarsi.ledstudio.util.AppPaths;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Verbindung, Autostart, Updates, Import und Diagnose. */
final class SettingsPage extends ScrollPane {
    private final AppCore core;
    private final Runnable onImported;
    private final Label updateStatus = Ui.label("", "muted");

    SettingsPage(AppCore core, UpdateController updates, Runnable onImported) {
        this.core = core;
        this.onImported = onImported;
        var p = core.project;

        TextField host = new TextField(p.host);
        host.textProperty().addListener((o, a, b) -> save(() -> p.host = b.strip()));
        TextField port = new TextField(Integer.toString(p.port));
        port.setPrefColumnCount(5);
        port.textProperty().addListener((o, a, b) -> {
            if (b.matches("\\d{1,5}")) save(() -> p.port = Integer.parseInt(b));
        });
        TextField path = new TextField(p.openrgbPath);
        path.setPromptText("Wird automatisch gesucht");
        HBox.setHgrow(path, Priority.ALWAYS);
        path.textProperty().addListener((o, a, b) -> save(() -> p.openrgbPath = b.strip()));
        CheckBox startOrgb = new CheckBox("OpenRGB automatisch starten");
        startOrgb.setSelected(p.startOpenrgb);
        startOrgb.selectedProperty().addListener((o, a, b) -> save(() -> p.startOpenrgb = b));

        CheckBox autostart = new CheckBox("Mit Windows starten (im Hintergrund)");
        autostart.setSelected(Autostart.enabled());
        autostart.setDisable(!Autostart.available());
        autostart.selectedProperty().addListener((o, a, b) -> {
            try {
                Autostart.set(b);
            } catch (RuntimeException e) {
                Ui.error(getScene().getWindow(), "Autostart", e.getMessage());
            }
        });
        CheckBox tray = new CheckBox("X minimiert ins Tray, statt zu beenden");
        tray.setSelected(p.closeToTray);
        tray.selectedProperty().addListener((o, a, b) -> save(() -> p.closeToTray = b));

        CheckBox auto = new CheckBox("Updates automatisch installieren, wenn das Fenster nicht offen ist");
        auto.setSelected(p.autoUpdate);
        auto.selectedProperty().addListener((o, a, b) -> save(() -> p.autoUpdate = b));
        updateStatus.textProperty().bind(updates.status);

        TextArea diag = new TextArea();
        diag.setEditable(false);
        diag.setPrefRowCount(10);
        diag.setWrapText(true);
        Runnable refreshDiag = () -> diag.setText(core.diagnose());
        refreshDiag.run();

        VBox box = new VBox(12,
                Ui.label("Einstellungen", "page-title"),
                Ui.section("OpenRGB"),
                Ui.row(Ui.field("SDK-Server", host), Ui.field("Port", port)),
                Ui.field("OpenRGB.exe", new HBox(6, path, Ui.button("Wählen…", () -> choosePath(path)))),
                startOrgb,
                Ui.button("Neu verbinden", core.connection::reconnect),
                Ui.section("Programm"),
                autostart, tray,
                Ui.section("Updates"),
                Ui.label("Installierte Version: " + Updater.currentVersion(), "muted"),
                auto, updateStatus,
                Ui.button("Jetzt nach Updates suchen", () -> updates.check(true)),
                Ui.section("Alte Version übernehmen"),
                wrap("Holt Vorlagen und Einstellungen aus der config.json deines alten LED Studio. Das Layout legst du danach mit „NZXT wie bisher“ an."),
                Ui.button("config.json importieren…", this::importLegacy),
                Ui.section("Eigene Effekte"),
                wrap("Lege JavaScript-Dateien in den Effekt-Ordner, die Funktion render(led, t, p) liefert [r, g, b]. Eine Vorlage liegt im Repository unter docs."),
                Ui.row(Ui.button("Effekt-Ordner öffnen", () -> open(AppPaths.effectsDir())), Ui.button("Effekte neu laden", this::reloadEffects)),
                Ui.section("Diagnose"),
                Ui.row(Ui.button("Testlicht (alle LEDs weiß, 3 s)", this::testLight), Ui.button("Aktualisieren", refreshDiag),
                        Ui.button("Log öffnen", () -> open(AppPaths.logFile()))),
                diag);
        box.getStyleClass().add("page-narrow");
        box.setMaxWidth(700);
        setContent(box);
        setFitToWidth(true);
        getStyleClass().add("panel");
        core.onDevices(refreshDiag);
    }

    private static Label wrap(String text) {
        Label l = Ui.label(text, "muted");
        l.setWrapText(true);
        return l;
    }

    private void save(Runnable r) {
        r.run();
        core.settingsChanged();
    }

    private void choosePath(TextField path) {
        FileChooser c = new FileChooser();
        c.setTitle("OpenRGB.exe auswählen");
        c.getExtensionFilters().add(new FileChooser.ExtensionFilter("OpenRGB", "OpenRGB.exe"));
        File f = c.showOpenDialog(getScene().getWindow());
        if (f != null) path.setText(f.getAbsolutePath());
    }

    private void importLegacy() {
        FileChooser c = new FileChooser();
        c.setTitle("config.json des alten LED Studio");
        c.getExtensionFilters().add(new FileChooser.ExtensionFilter("Konfiguration", "*.json"));
        File f = c.showOpenDialog(getScene().getWindow());
        if (f == null) return;
        try {
            int n = LegacyImport.apply(core.project, f.toPath());
            core.settingsChanged();
            onImported.run();
            Ui.info(getScene().getWindow(), "Import", n + " Vorlagen übernommen.");
        } catch (Exception e) {
            Ui.error(getScene().getWindow(), "Import fehlgeschlagen", e.getMessage());
        }
    }

    private void reloadEffects() {
        List<String> errors = ScriptEffects.load(AppPaths.effectsDir());
        onImported.run();
        Ui.info(getScene().getWindow(), "Effekte", errors.isEmpty() ? "Effekte neu geladen." : String.join("\n", errors));
    }

    private void testLight() {
        Set<Integer> all = Set.copyOf(IntStream.range(0, core.compiled().leds.length).boxed().toList());
        core.engine.identify(all, 3000);
    }

    private void open(Path path) {
        try {
            if (!Files.exists(path)) Files.createFile(path);
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException e) {
            Ui.error(getScene().getWindow(), "Öffnen fehlgeschlagen", e.getMessage());
        }
    }
}
