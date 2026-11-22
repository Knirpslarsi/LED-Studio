package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.plugins.Plugins;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plugin-Manager: Plugins aus GitHub oder dem Katalog installieren, verwalten, aktualisieren und selbst bauen. */
final class PluginsPage extends ScrollPane {
    static final String GUIDE = "https://github.com/Knirpslarsi/LED-Studio/blob/main/docs/plugins.md";

    private final AppCore core;
    private final VBox box = new VBox(12);
    private final TextField repo = new TextField();
    private final Label status = muted("");
    private List<Plugins.CatalogEntry> catalog;
    private String catalogError = "";
    /** Gefundene Updates: Plugin-ID → neue Version. */
    private final Map<String, String> updates = new HashMap<>();
    private boolean busy;

    PluginsPage(AppCore core) {
        this.core = core;
        repo.setPromptText("name/repo oder https://github.com/name/repo");
        repo.setOnAction(e -> installRepo(repo.getText()));
        box.getStyleClass().add("page-narrow");
        box.setMaxWidth(720);
        setContent(box);
        setFitToWidth(true);
        build();
        core.onFeatures(this::build);
        sceneProperty().addListener((o, a, b) -> {
            if (b != null && catalog == null && catalogError.isBlank()) loadCatalog();
        });
    }

    private void build() {
        box.getChildren().setAll(
                Ui.label("Plugins", "page-title"),
                muted("Plugins bringen neue Effekte und Vorlagen. Effekte aus Plugins laufen abgeschottet: Sie können nur "
                        + "Farben berechnen und haben keinen Zugriff auf Dateien, Internet oder deinen PC."),
                status);
        official();
        install();
        installedList();
        discover();
        create();
    }

    // ---- Mitgelieferte Plugins

    private void official() {
        List<Plugins.Builtin> missing = Plugins.BUILTINS.stream()
                .filter(b -> b.offered() && !core.plugins.featureInstalled(b.feature())).toList();
        if (missing.isEmpty()) return;
        box.getChildren().addAll(Ui.section("Offizielle Plugins"),
                muted("Gehören zu LED Studio und sind sofort da, ohne Download."));
        for (Plugins.Builtin b : missing) {
            VBox card = new VBox(4);
            card.getStyleClass().add("layer-row");
            card.setStyle("-fx-padding: 10;");
            card.getChildren().addAll(
                    Ui.row(Ui.label(b.name(), "field-label"), Ui.grow(), Ui.primary("Installieren", () -> {
                        try {
                            core.plugins.installBuiltin(b.feature());
                            core.featuresChanged();
                            status.setText("„" + b.name() + "“ installiert. Die Seite steht jetzt links in der Navigation.");
                        } catch (Exception e) {
                            Ui.error(getScene().getWindow(), "Plugin installieren", e.getMessage());
                        }
                        build();
                    })),
                    muted(b.description()));
            box.getChildren().add(card);
        }
    }

    // ---- Installieren

    private void install() {
        Button go = Ui.primary("Installieren", () -> installRepo(repo.getText()));
        HBox.setHgrow(repo, Priority.ALWAYS);
        box.getChildren().addAll(Ui.section("Plugin installieren"),
                muted("Aus einem GitHub-Repo: LED Studio lädt das neueste Release, ohne Release den Hauptzweig."),
                Ui.row(repo, go),
                Ui.row(Ui.button("Aus ZIP-Datei …", this::installZip)));
    }

    private void installRepo(String input) {
        if (busy || input == null || input.isBlank()) return;
        String name;
        try {
            name = Plugins.parseRepo(input);
        } catch (IllegalArgumentException e) {
            Ui.error(getScene().getWindow(), "Plugin installieren", e.getMessage());
            return;
        }
        run("Lade " + name + " …", () -> {
            Plugins.Installed p = core.plugins.installFromGitHub(name);
            Platform.runLater(() -> installed(p, "„" + p.manifest().name + "“ " + p.manifest().version + " installiert."));
        });
    }

    private void installZip() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Plugin als ZIP");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f == null) return;
        run("Installiere " + f.getName() + " …", () -> {
            Plugins.Installed p = core.plugins.installFromZip(f.toPath());
            Platform.runLater(() -> installed(p, "„" + p.manifest().name + "“ installiert."));
        });
    }

    /** Nach dem Installieren: Vorlagen übernehmen, Effekte laden, Seite neu aufbauen. */
    private void installed(Plugins.Installed p, String message) {
        List<String> added = new ArrayList<>();
        try {
            for (Project.Preset preset : core.plugins.presets(p)) {
                // Eine Vorlage aus einer früheren Version dieses Plugins wird ersetzt, sonst kommt sie mit neuem Namen dazu.
                Project.Preset old = core.project.preset(preset.name);
                if (old != null && p.source().presets.contains(preset.name)) core.project.presets.remove(old);
                Project.Preset copy = preset.copy(core.project.uniquePresetName(preset.name));
                core.project.presets.add(copy);
                added.add(copy.name);
            }
            core.plugins.rememberPresets(p.id(), added);
        } catch (Exception e) {
            message += " Vorlagen konnten nicht übernommen werden: " + e.getMessage();
        }
        updates.remove(p.id());
        List<String> errors = core.reloadEffects();
        core.settingsChanged();
        repo.clear();
        String effects = p.manifest().effects.size() + " Effekt" + (p.manifest().effects.size() == 1 ? "" : "e");
        status.setText(message + " " + effects + (added.isEmpty() ? "" : ", " + added.size() + " Vorlage(n)") + "."
                + (errors.isEmpty() ? "" : " Fehler: " + String.join("; ", errors)));
        build();
    }

    // ---- Installierte

    private void installedList() {
        List<Plugins.Installed> list = core.plugins.installed();
        Button check = Ui.button("Nach Updates suchen", this::checkUpdates);
        check.setDisable(list.stream().noneMatch(p -> "github".equals(p.source().kind)));
        box.getChildren().addAll(Ui.section("Installiert"), Ui.row(muted(list.isEmpty() ? "Noch keine Plugins installiert." : list.size() + " Plugin(s)"),
                Ui.grow(), check, Ui.button("Neu laden", () -> {
                    List<String> errors = core.reloadEffects();
                    core.settingsChanged();
                    status.setText(errors.isEmpty() ? "Effekte neu geladen." : "Fehler: " + String.join("; ", errors));
                    build();
                })));
        for (Plugins.Installed p : list) box.getChildren().add(card(p));
    }

    private Node card(Plugins.Installed p) {
        Plugins.Manifest m = p.manifest();
        Plugins.Source s = p.source();
        VBox card = new VBox(6);
        card.getStyleClass().add("layer-row");
        card.setStyle("-fx-padding: 10;");
        CheckBox on = new CheckBox(m.name + "  " + m.version);
        on.setSelected(s.enabled);
        on.selectedProperty().addListener((o, a, b) -> {
            try {
                core.plugins.setEnabled(p.id(), b);
                core.reloadEffects();
                core.settingsChanged();
                core.featuresChanged();
            } catch (Exception e) {
                Ui.error(getScene().getWindow(), "Plugin", e.getMessage());
            }
        });
        String origin = switch (s.kind) {
            case "github" -> "GitHub: " + s.repo + (s.ref.isBlank() ? "" : " (" + s.ref + ")");
            case "zip" -> "aus ZIP-Datei";
            case "builtin" -> "offizielles Plugin, mitgeliefert";
            default -> "selbst gebaut";
        };
        String by = m.author.isBlank() ? "" : "von " + m.author + " · ";
        List<Node> buttons = new ArrayList<>();
        String update = updates.get(p.id());
        if (update != null) buttons.add(Ui.primary("Update auf " + update, () -> installRepo(s.repo)));
        if ("github".equals(s.kind)) buttons.add(Ui.button("Auf GitHub", () -> browse("https://github.com/" + s.repo)));
        if ("local".equals(s.kind)) buttons.add(Ui.button("Vorlagen ins Plugin …", () -> exportPresets(p)));
        if (m.builtin.isBlank()) buttons.add(Ui.button("Ordner", () -> open(p.dir())));
        buttons.add(Ui.button("Entfernen", () -> uninstall(p)));
        boolean builtin = !m.builtin.isBlank();
        card.getChildren().addAll(Ui.row(on), muted(builtin ? origin : by + m.effects.size() + " Effekt(e) · " + origin));
        if (!m.description.isBlank()) card.getChildren().add(muted(m.description));
        List<Node> row = new ArrayList<>();
        row.add(Ui.grow());
        row.addAll(buttons);
        card.getChildren().add(Ui.row(row.toArray(Node[]::new)));
        return card;
    }

    private void checkUpdates() {
        run("Suche nach Updates …", () -> {
            Map<String, String> found = new HashMap<>();
            for (Plugins.Installed p : core.plugins.installed()) {
                String v = core.plugins.availableUpdate(p);
                if (v != null) found.put(p.id(), v);
            }
            Platform.runLater(() -> {
                updates.clear();
                updates.putAll(found);
                status.setText(found.isEmpty() ? "Alle Plugins sind aktuell." : found.size() + " Update(s) gefunden.");
                build();
            });
        });
    }

    private void uninstall(Plugins.Installed p) {
        if (!Ui.confirm(getScene().getWindow(), "Plugin entfernen", "„" + p.manifest().name + "“ entfernen?"
                + (p.source().presets.isEmpty() ? "" : "\nDie Vorlagen aus dem Plugin (" + String.join(", ", p.source().presets)
                + ") werden auch entfernt.")
                + ("local".equals(p.source().kind) ? "\n\nAchtung: Es ist selbst gebaut, der Ordner wird gelöscht." : "")
                + (p.manifest().builtin.isBlank() ? "" : "\n\nDie Seite verschwindet. Deine Einstellungen und Shows bleiben gespeichert "
                + "und sind nach dem erneuten Installieren wieder da."))) return;
        try {
            for (String name : p.source().presets) {
                Project.Preset preset = core.project.preset(name);
                if (preset != null) core.project.presets.remove(preset);
            }
            if (core.project.preset(core.project.current) == null && !Project.OFF.equals(core.project.current) && !core.project.presets.isEmpty())
                core.project.current = core.project.presets.get(0).name;
            core.plugins.uninstall(p.id());
            core.reloadEffects();
            core.settingsChanged();
            core.featuresChanged();
            status.setText("„" + p.manifest().name + "“ entfernt.");
        } catch (Exception e) {
            Ui.error(getScene().getWindow(), "Entfernen fehlgeschlagen", e.getMessage());
        }
        build();
    }

    // ---- Katalog

    private void discover() {
        box.getChildren().addAll(Ui.section("Plugins entdecken"),
                Ui.row(muted("Plugins aus dem Katalog " + Plugins.CATALOG_REPO + "."), Ui.grow(), Ui.button("Aktualisieren", this::loadCatalog)));
        if (!catalogError.isBlank()) {
            box.getChildren().add(muted(catalogError));
            return;
        }
        if (catalog == null) {
            box.getChildren().add(muted("Lade Katalog …"));
            return;
        }
        if (catalog.isEmpty()) {
            box.getChildren().add(muted("Im Katalog sind noch keine Plugins. Plugins aus einem GitHub-Repo lassen sich oben trotzdem installieren."));
            return;
        }
        List<String> have = new ArrayList<>();
        for (Plugins.Installed p : core.plugins.installed()) have.add(p.source().repo.toLowerCase());
        for (Plugins.CatalogEntry e : catalog) {
            VBox card = new VBox(4);
            card.getStyleClass().add("layer-row");
            card.setStyle("-fx-padding: 10;");
            boolean installed = have.contains(e.repo().toLowerCase());
            Button go = installed ? Ui.button("Installiert", () -> {
            }) : Ui.primary("Installieren", () -> installRepo(e.repo()));
            go.setDisable(installed);
            card.getChildren().addAll(Ui.row(Ui.label(e.name().isBlank() ? e.repo() : e.name(), "field-label"), Ui.grow(),
                            Ui.button("Ansehen", () -> browse("https://github.com/" + e.repo())), go),
                    muted((e.author().isBlank() ? "" : "von " + e.author() + " · ") + e.repo()));
            if (!e.description().isBlank()) card.getChildren().add(muted(e.description()));
            box.getChildren().add(card);
        }
    }

    private void loadCatalog() {
        catalogError = "";
        core.background(() -> {
            List<Plugins.CatalogEntry> list = core.plugins.catalog();
            Platform.runLater(() -> {
                catalog = list;
                build();
            });
        }, null, err -> {
            catalog = null;
            catalogError = "Katalog nicht erreichbar: " + err;
            build();
        });
    }

    // ---- Eigenes Plugin

    private void create() {
        TextField name = new TextField();
        name.setPromptText("Name, z. B. Meine Neon-Effekte");
        TextField author = new TextField();
        author.setPromptText("Dein Name (optional)");
        HBox.setHgrow(name, Priority.ALWAYS);
        box.getChildren().addAll(Ui.section("Eigenes Plugin erstellen"),
                muted("1. Plugin anlegen: Es bekommt einen Ordner mit plugin.json und einem Beispiel-Effekt und ist sofort aktiv.\n"
                        + "2. Effekte als .js-Datei in den Ordner effects/ legen und in plugin.json eintragen, dann „Neu laden“.\n"
                        + "3. Optional eigene Vorlagen mit „Vorlagen ins Plugin …“ mitgeben.\n"
                        + "4. Den Ordner als öffentliches GitHub-Repo hochladen. Andere installieren es dann oben mit name/repo. "
                        + "Neue Versionen: version in plugin.json erhöhen und ein Release mit Tag (z. B. v1.0.1) anlegen."),
                Ui.row(name, author, Ui.primary("Erstellen", () -> {
                    try {
                        Plugins.Installed p = core.plugins.create(name.getText(), author.getText());
                        core.reloadEffects();
                        core.settingsChanged();
                        status.setText("„" + p.manifest().name + "“ angelegt. Der Beispiel-Effekt heißt „Beispiel: Farbwelle“.");
                        build();
                        open(p.dir());
                    } catch (Exception e) {
                        Ui.error(getScene().getWindow(), "Plugin erstellen", e.getMessage());
                    }
                })),
                Ui.row(Ui.button("Anleitung für Plugins", () -> browse(GUIDE)), Ui.button("Plugin-Ordner öffnen", () -> open(core.plugins.root()))));
    }

    private void exportPresets(Plugins.Installed p) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(getScene().getWindow());
        d.setTitle("Vorlagen ins Plugin");
        d.setHeaderText("Welche Vorlagen sollen mit „" + p.manifest().name + "“ verteilt werden?");
        VBox list = new VBox(6);
        List<CheckBox> boxes = new ArrayList<>();
        for (Project.Preset preset : core.project.presets) {
            CheckBox c = new CheckBox(preset.name);
            c.setUserData(preset);
            boxes.add(c);
            list.getChildren().add(c);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setPrefSize(380, 320);
        d.getDialogPane().setContent(scroll);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        List<Project.Preset> chosen = new ArrayList<>();
        for (CheckBox c : boxes) if (c.isSelected()) chosen.add((Project.Preset) c.getUserData());
        try {
            core.plugins.exportPresets(p, chosen);
            status.setText(chosen.size() + " Vorlage(n) in „" + p.manifest().name + "“ gespeichert (presets.json).");
        } catch (Exception e) {
            Ui.error(getScene().getWindow(), "Vorlagen speichern", e.getMessage());
        }
    }

    // ---- Hilfen

    private void run(String message, AppCore.Task task) {
        busy = true;
        status.setText(message);
        core.background(() -> {
            try {
                task.run();
            } finally {
                Platform.runLater(() -> busy = false);
            }
        }, null, err -> {
            status.setText("Fehlgeschlagen: " + err);
            Ui.error(getScene().getWindow(), "Plugins", err);
        });
    }

    private static void open(Path dir) {
        new Thread(() -> {
            try {
                java.nio.file.Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
            } catch (Exception ignored) {
            }
        }, "open-folder").start();
    }

    private static void browse(String url) {
        new Thread(() -> {
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception ignored) {
            }
        }, "browse").start();
    }

    private static Label muted(String text) {
        return AnimationEditor.muted(text);
    }
}
