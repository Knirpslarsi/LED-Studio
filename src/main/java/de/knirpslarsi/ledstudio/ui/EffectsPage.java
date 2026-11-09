package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.ParamSpec;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.layout.Targets;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.util.Colors;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Vorlagen und ihre Ebenen: welcher Effekt auf welchem Gerät, mit welchen Einstellungen. */
final class EffectsPage extends SplitPane {
    private final AppCore core;
    private final ListView<String> list = new ListView<>();
    private final LayoutCanvas preview;
    private final VBox layerRows = new VBox(4);
    private final VBox effectForm = new VBox(10), targetForm = new VBox(6), fineForm = new VBox(10);
    private final Label title = Ui.label("", "page-title");
    private final Label blurb = Ui.label("", "muted");
    private final TabPane tabs;
    private Project.Layer selected;
    private boolean updating;

    EffectsPage(AppCore core, Runnable goToLayout) {
        this.core = core;
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : Project.OFF.equals(item) ? "Aus" : item);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && !updating) {
                core.engine.solo(null);
                core.selectPreset(b);
                selected = null;
                showPreset();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox left = new VBox(8, Ui.section("Vorlagen"), list,
                Ui.row(Ui.button("Neu", this::newPreset), Ui.button("Kopieren", this::copyPreset)),
                Ui.row(Ui.button("Umbenennen", this::renamePreset), Ui.button("Löschen", this::deletePreset)));
        left.getStyleClass().add("panel-content");

        preview = new LayoutCanvas(core, false);
        preview.onFixtureClick(this::togglePreviewFixture);
        VBox.setVgrow(preview, Priority.ALWAYS);
        blurb.setWrapText(true);
        ScrollPane rowScroll = new ScrollPane(layerRows);
        rowScroll.setFitToWidth(true);
        rowScroll.setPrefHeight(170);
        rowScroll.setMinHeight(90);
        rowScroll.getStyleClass().add("panel");
        Label hint = Ui.label("Klick auf ein Bauteil in der Vorschau nimmt es ins Ziel der gewählten Ebene auf oder heraus.", "muted");
        hint.setWrapText(true);
        VBox middle = new VBox(6, title, blurb, preview, hint,
                Ui.row(Ui.section("Ebenen"), Ui.label("oben liegt über unten", "muted"), Ui.grow(), Ui.button("Layout bearbeiten", goToLayout)),
                rowScroll,
                Ui.row(Ui.primary("+ Ebene", this::addLayer), Ui.button("Duplizieren", this::duplicateLayer),
                        Ui.button("▲", "Weiter nach oben", () -> moveLayer(-1)), Ui.button("▼", "Weiter nach unten", () -> moveLayer(1)),
                        Ui.button("Entfernen", this::removeLayer),
                        Ui.button("Je Gerät eine Ebene", "Teilt die gewählte Ebene auf: Danach hat jedes Gerät seine eigene und du kannst den Effekt je Gerät ändern", this::splitByDevice)));
        middle.getStyleClass().add("panel-content");

        tabs = new TabPane(tab("Effekt", effectForm), tab("Ziel", targetForm), tab("Feinschliff", fineForm));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        getItems().addAll(left, middle, tabs);
        setDividerPositions(0.17, 0.7);
        SplitPane.setResizableWithParent(left, false);
        SplitPane.setResizableWithParent(tabs, false);
        left.setMinWidth(180);
        tabs.setMinWidth(320);

        core.onDevices(this::showPreset);
        core.onLayout(this::refreshTarget);
        refreshList();
        showPreset();
    }

    private static Tab tab(String name, VBox content) {
        content.getStyleClass().add("panel-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel");
        return new Tab(name, scroll);
    }

    void refreshList() {
        updating = true;
        List<String> names = new ArrayList<>();
        core.project.presets.forEach(p -> names.add(p.name));
        names.add(Project.OFF);
        list.setItems(FXCollections.observableArrayList(names));
        list.getSelectionModel().select(core.project.current);
        updating = false;
    }

    /** Für Screenshots: wählt einen Reiter rechts. */
    void showTab(int index) {
        tabs.getSelectionModel().select(index);
    }

    /** Für Screenshots: im aktuellen Reiter ganz nach unten scrollen. */
    void scrollToEnd() {
        if (tabs.getSelectionModel().getSelectedItem().getContent() instanceof ScrollPane sp) sp.setVvalue(1);
    }

    void externalChange() {
        refreshList();
        selected = null;
        showPreset();
    }

    private Project.Preset preset() {
        return core.project.preset(core.project.current);
    }

    private void showPreset() {
        Project.Preset p = preset();
        if (p == null) {
            title.setText("Aus");
            blurb.setText("Alle Bauteile im Layout bleiben dunkel.");
            selected = null;
        } else {
            title.setText(p.name);
            List<Project.Layer> layers = p.layers();
            if (selected == null || !layers.contains(selected)) selected = layers.isEmpty() ? null : layers.get(0);
        }
        refreshRows();
        showLayer();
    }

    // ---- Ebenenliste

    private String targetSummary(Project.Layer l) {
        if (l.targets.isEmpty()) return "alles";
        if (l.targets.contains(Targets.NONE)) return "nichts gewählt";
        List<String> parts = new ArrayList<>();
        for (String t : l.targets) {
            if (t.startsWith("device:")) parts.add(deviceName(t.substring(7)));
            else if (t.startsWith("zone:")) parts.add(t.substring(t.lastIndexOf('#') + 1));
            else if (t.startsWith("fixture:")) {
                String id = t.substring(8);
                core.project.fixtures.stream().filter(f -> f.id.equals(id)).findFirst().ifPresent(f -> parts.add(f.name));
            }
        }
        return parts.isEmpty() ? "Gerät fehlt" : String.join(", ", parts);
    }

    private String deviceName(String key) {
        Controller c = LayoutTools.findController(key, core.controllers());
        if (c != null) return c.displayName();
        int bar = key.indexOf('|');
        String name = bar > 0 ? key.substring(0, bar) : key;
        return name.isBlank() ? "Gerät" : name;
    }

    private String effectTitle(Project.Layer l) {
        Effect e = Effects.create(l.effect);
        return e == null ? l.effect + " (nicht gefunden)" : e.title();
    }

    private void refreshRows() {
        layerRows.getChildren().clear();
        Project.Preset p = preset();
        if (p == null) return;
        for (Project.Layer l : p.layers()) {
            CheckBox on = new CheckBox();
            on.setSelected(l.enabled);
            on.setOnAction(e -> {
                l.enabled = on.isSelected();
                core.settingsChanged();
            });
            Label name = new Label((l.name.isBlank() ? effectTitle(l) : l.name) + "  →  " + targetSummary(l));
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            ToggleButton solo = new ToggleButton("Solo");
            solo.setTooltip(new javafx.scene.control.Tooltip("Zeigt zum Einstellen nur diese Ebene"));
            solo.setSelected(l.id.equals(core.engine.solo()));
            solo.setOnAction(e -> {
                core.engine.solo(solo.isSelected() ? l.id : null);
                refreshRows();
            });
            HBox row = new HBox(8, on, name, solo);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            row.getStyleClass().add("layer-row");
            if (l == selected) row.getStyleClass().add("selected");
            row.setOnMouseClicked(e -> {
                selected = l;
                refreshRows();
                showLayer();
            });
            layerRows.getChildren().add(row);
        }
    }

    private void layersChanged() {
        core.settingsChanged();
        refreshRows();
        showLayer();
    }

    private void addLayer() {
        Project.Preset p = preset();
        if (p == null) return;
        Project.Layer l = new Project.Layer();
        l.effect = "rotor";
        // Eine neue Ebene obenauf würde sonst alles verdecken: Sie startet leer, und das Ziel wählst du gleich rechts.
        l.targets = new ArrayList<>(List.of(Targets.NONE));
        p.layers().add(0, l);
        selected = l;
        layersChanged();
        tabs.getSelectionModel().select(1);
    }

    private void duplicateLayer() {
        Project.Preset p = preset();
        if (p == null || selected == null) return;
        Project.Layer c = selected.copy();
        List<Project.Layer> layers = p.layers();
        layers.add(layers.indexOf(selected), c);
        selected = c;
        layersChanged();
    }

    private void removeLayer() {
        Project.Preset p = preset();
        if (p == null || selected == null) return;
        p.layers().remove(selected);
        core.engine.solo(null);
        selected = null;
        core.settingsChanged();
        showPreset();
    }

    private void moveLayer(int delta) {
        Project.Preset p = preset();
        if (p == null || selected == null) return;
        List<Project.Layer> layers = p.layers();
        int i = layers.indexOf(selected), j = i + delta;
        if (j < 0 || j >= layers.size()) return;
        layers.set(i, layers.get(j));
        layers.set(j, selected);
        layersChanged();
    }

    private void splitByDevice() {
        Project.Preset p = preset();
        if (p == null || selected == null) return;
        Set<String> ids = Targets.resolve(selected.targets, core.project.fixtures);
        Set<String> keys = new LinkedHashSet<>();
        for (Fixture f : core.project.fixtures) if (ids.contains(f.id)) keys.add(f.deviceKey);
        if (keys.size() < 2) {
            Ui.info(getScene().getWindow(), "Je Gerät eine Ebene", "Diese Ebene deckt nur ein Gerät ab. Hole weitere Geräte unter „Ziel“ dazu.");
            return;
        }
        List<Project.Layer> layers = p.layers();
        int at = layers.indexOf(selected);
        layers.remove(selected);
        Project.Layer first = null;
        for (String key : keys) {
            Project.Layer c = selected.copy();
            c.targets = new ArrayList<>(List.of(Targets.device(key)));
            layers.add(at++, c);
            if (first == null) first = c;
        }
        selected = first;
        layersChanged();
    }

    // ---- Effekt

    private void showLayer() {
        boolean has = selected != null;
        effectForm.getChildren().clear();
        fineForm.getChildren().clear();
        refreshTarget();
        if (!has) {
            preview.setHighlight(Set.of());
            if (preset() != null) effectForm.getChildren().add(Ui.label("Lege mit „+ Ebene“ einen Effekt an.", "muted"));
            return;
        }
        buildEffectForm();
        buildFineForm();
    }

    private void buildEffectForm() {
        Project.Layer l = selected;
        Effect effect = Effects.create(l.effect);
        blurb.setText(effect == null ? "Effekt „" + l.effect + "“ nicht gefunden." : effect.blurb());
        TextField name = new TextField(l.name);
        name.setPromptText("Automatisch: Effektname");
        name.textProperty().addListener((o, a, b) -> {
            l.name = b.strip();
            refreshRowsKeepFocus();
            core.settingsChanged();
        });
        Map<String, Effect> catalog = new LinkedHashMap<>();
        Effects.catalog().forEach(e -> catalog.put(e.title(), e));
        ComboBox<String> chooser = new ComboBox<>(FXCollections.observableArrayList(catalog.keySet()));
        chooser.setMaxWidth(Double.MAX_VALUE);
        if (effect != null) chooser.setValue(effect.title());
        chooser.setOnAction(e -> {
            Effect chosen = catalog.get(chooser.getValue());
            if (chosen != null && !chosen.id().equals(l.effect)) {
                l.effect = chosen.id();
                l.params = new LinkedHashMap<>();
                layersChanged();
            }
        });
        effectForm.getChildren().addAll(Ui.field("Name der Ebene", name), Ui.section("Effekt"), chooser);
        if (effect == null) return;
        VBox settings = new VBox();
        settings.spacingProperty().bind(effectForm.spacingProperty());
        effectForm.getChildren().add(settings);
        fillSettings(settings, effect, l);
        Button reset = Ui.button("Effekt-Einstellungen zurücksetzen", () -> {
            l.params = new LinkedHashMap<>();
            layersChanged();
        });
        reset.setMaxWidth(Double.MAX_VALUE);
        effectForm.getChildren().add(reset);
    }

    /** Einstellungen des Effekts, mit Überschriften; was zur aktuellen Auswahl nicht passt, bleibt verborgen. */
    private void fillSettings(VBox box, Effect effect, Project.Layer l) {
        box.getChildren().clear();
        Params values = new Params(effect.params(), l.params);
        java.util.Set<String> drivers = new java.util.HashSet<>();
        for (ParamSpec spec : effect.params()) if (spec.showIfParam != null) drivers.add(spec.showIfParam);
        String section = "Einstellungen", shown = null;
        for (ParamSpec spec : effect.params()) {
            if (spec.section != null) section = spec.section;
            if (!spec.visible(values)) continue;
            if (!section.equals(shown)) {
                box.getChildren().add(Ui.section(section));
                shown = section;
            }
            Runnable rebuild = drivers.contains(spec.name) ? () -> javafx.application.Platform.runLater(() -> fillSettings(box, effect, l)) : null;
            box.getChildren().add(control(spec, values, l, rebuild));
        }
    }

    private void refreshRowsKeepFocus() {
        // Nur die Zeilen neu aufbauen; das Formular rechts behält den Fokus.
        refreshRows();
    }

    private void set(Project.Layer l, String name, Object value) {
        l.params.put(name, value);
        core.settingsChanged();
    }

    private Node control(ParamSpec s, Params values, Project.Layer l, Runnable onChoice) {
        Node n = switch (s.type) {
            case FLOAT, INT -> Ui.slider(s.label, s.min, s.max, values.d(s.name), s.step, v -> set(l, s.name, v));
            case HUE -> hue(s, values, l);
            case BOOL -> {
                CheckBox c = new CheckBox(s.label);
                c.setSelected(values.b(s.name));
                c.selectedProperty().addListener((o, a, b) -> set(l, s.name, b ? 1 : 0));
                yield c;
            }
            case COLOR -> colorField(s.label, values.color(s.name), v -> set(l, s.name, v));
            case CHOICE -> choice(s, values, l, onChoice);
        };
        if (s.help == null) return n;
        Label help = Ui.label(s.help, "muted");
        help.setWrapText(true);
        return new VBox(2, n, help);
    }

    private Node hue(ParamSpec s, Params values, Project.Layer l) {
        Slider slider = new Slider(0, 1, values.d(s.name));
        slider.getStyleClass().add("hue-slider");
        slider.setMaxWidth(Double.MAX_VALUE);
        Label swatch = new Label();
        swatch.setMinSize(18, 18);
        Runnable paint = () -> swatch.setStyle("-fx-background-radius: 9; -fx-background-color: "
                + Colors.toHex(Colors.hsv(slider.getValue(), 1, 1)) + ";");
        paint.run();
        slider.valueProperty().addListener((o, a, b) -> {
            paint.run();
            set(l, s.name, Math.round(b.doubleValue() * 1000) / 1000.0);
        });
        HBox head = new HBox(6, Ui.label(s.label, "field-label"), Ui.grow(), swatch);
        return new VBox(3, head, slider);
    }

    private Node colorField(String label, int value, Consumer<String> onChange) {
        ColorPicker picker = new ColorPicker(Ui.fx(value));
        picker.setPrefWidth(46);
        picker.getStyleClass().add("button");
        TextField hex = new TextField(Colors.toHex(value));
        hex.setPrefColumnCount(7);
        HBox.setHgrow(hex, Priority.ALWAYS);
        picker.setOnAction(e -> {
            String text = Colors.toHex(Ui.rgb(picker.getValue()));
            hex.setText(text);
            onChange.accept(text);
        });
        Runnable commit = () -> {
            try {
                int c = Colors.parseHex(hex.getText());
                picker.setValue(Ui.fx(c));
                hex.setText(Colors.toHex(c));
                onChange.accept(Colors.toHex(c));
            } catch (RuntimeException ex) {
                hex.setText(Colors.toHex(Ui.rgb(picker.getValue()))); // ungültige Werte zurücksetzen
            }
        };
        hex.setOnAction(e -> commit.run());
        hex.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) commit.run();
        });
        return Ui.field(label, new HBox(6, picker, hex));
    }

    private Node choice(ParamSpec s, Params values, Project.Layer l, Runnable onChoice) {
        List<String> keys = new ArrayList<>(s.choices.keySet());
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(s.choices.values()));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setValue(s.choices.getOrDefault(values.s(s.name), s.choices.values().iterator().next()));
        box.setOnAction(e -> {
            int i = box.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                set(l, s.name, keys.get(i));
                if (onChoice != null) onChoice.run();
            }
        });
        return Ui.field(s.label, box);
    }

    // ---- Ziel

    private void refreshTarget() {
        targetForm.getChildren().clear();
        Project.Layer l = selected;
        if (l == null) return;
        List<Fixture> fixtures = core.project.fixtures;
        Set<String> chosen = Targets.resolve(l.targets, fixtures);
        preview.setHighlight(chosen);

        targetForm.getChildren().add(Ui.section("Wo läuft diese Ebene?"));
        CheckBox all = new CheckBox("Alles im Layout");
        setState(all, fixtures.stream().map(f -> f.id).collect(Collectors.toSet()), chosen);
        all.setOnAction(e -> toggle(fixtures.stream().map(f -> f.id).collect(Collectors.toSet()), all.isSelected()));
        targetForm.getChildren().add(all);

        Map<String, Map<String, List<Fixture>>> tree = new LinkedHashMap<>();
        for (Fixture f : fixtures) {
            tree.computeIfAbsent(f.deviceKey, k -> new LinkedHashMap<>()).computeIfAbsent(f.zoneName, k -> new ArrayList<>()).add(f);
        }
        tree.forEach((key, zones) -> {
            Set<String> deviceIds = zones.values().stream().flatMap(List::stream).map(f -> f.id).collect(Collectors.toSet());
            CheckBox device = new CheckBox(deviceName(key));
            setState(device, deviceIds, chosen);
            device.setOnAction(e -> toggle(deviceIds, device.isSelected()));
            device.setStyle("-fx-font-weight: bold;");
            targetForm.getChildren().add(device);
            zones.forEach((zone, inZone) -> {
                Set<String> zoneIds = inZone.stream().map(f -> f.id).collect(Collectors.toSet());
                if (zones.size() > 1 || inZone.size() > 1) {
                    CheckBox z = new CheckBox(zone);
                    setState(z, zoneIds, chosen);
                    z.setOnAction(e -> toggle(zoneIds, z.isSelected()));
                    z.getStyleClass().add("target-indent-1");
                    targetForm.getChildren().add(z);
                }
                if (inZone.size() > 1) {
                    for (Fixture f : inZone) {
                        CheckBox c = new CheckBox(f.name);
                        c.setSelected(chosen.contains(f.id));
                        c.setOnAction(e -> toggle(Set.of(f.id), c.isSelected()));
                        c.getStyleClass().add("target-indent-2");
                        targetForm.getChildren().add(c);
                    }
                }
            });
        });
        if (fixtures.isEmpty()) {
            targetForm.getChildren().add(Ui.label("Noch keine Bauteile im Layout.", "muted"));
        }

        // Geräte, die noch nicht im Layout sind, direkt von hier holen.
        Set<String> placed = fixtures.stream().map(f -> f.deviceKey).collect(Collectors.toSet());
        List<Controller> missing = new ArrayList<>();
        for (Controller c : core.controllers()) {
            if (!placed.contains(c.key()) && c.ledCount() > 0) missing.add(c);
        }
        if (!missing.isEmpty()) {
            targetForm.getChildren().add(Ui.section("Noch nicht im Layout"));
            Label why = Ui.label("Ein Gerät muss im Layout sein, damit eine Ebene es ansteuern kann.", "muted");
            why.setWrapText(true);
            targetForm.getChildren().add(why);
            for (Controller c : missing) {
                Button add = Ui.button("+ " + c.displayName(), () -> addDevice(c));
                add.setMaxWidth(Double.MAX_VALUE);
                targetForm.getChildren().add(add);
            }
        }
    }

    private static void setState(CheckBox box, Set<String> ids, Set<String> chosen) {
        boolean any = ids.stream().anyMatch(chosen::contains), every = !ids.isEmpty() && chosen.containsAll(ids);
        box.setAllowIndeterminate(false);
        box.setSelected(every);
        box.setIndeterminate(any && !every);
    }

    private void toggle(Set<String> ids, boolean on) {
        selected.targets = Targets.toggle(selected.targets, core.project.fixtures, ids, on);
        core.settingsChanged();
        refreshRows();
        refreshTarget();
    }

    private void togglePreviewFixture(Fixture f) {
        if (selected == null) return;
        boolean inside = Targets.resolve(selected.targets, core.project.fixtures).contains(f.id);
        toggle(Set.of(f.id), !inside);
    }

    private void addDevice(Controller c) {
        core.snapshot();
        double y = core.project.fixtures.stream().mapToDouble(f -> f.y + f.height / 2).max().orElse(-100) + 110;
        List<Fixture> created = LayoutTools.fromDevice(c, y);
        core.project.fixtures.addAll(created);
        core.layoutChanged();
        if (selected != null) toggle(created.stream().map(f -> f.id).collect(Collectors.toSet()), true);
    }

    // ---- Feinschliff

    private void fine(Runnable r) {
        r.run();
        core.settingsChanged();
    }

    private ComboBox<String> options(Map<String, String> values, String current, Consumer<String> onChange) {
        List<String> keys = new ArrayList<>(values.keySet());
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(values.values()));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setValue(values.getOrDefault(current, values.values().iterator().next()));
        box.setOnAction(e -> {
            int i = box.getSelectionModel().getSelectedIndex();
            if (i >= 0) onChange.accept(keys.get(i));
        });
        return box;
    }

    private static Map<String, String> pairs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private void buildFineForm() {
        Project.Layer l = selected;
        VBox colors = new VBox(8);
        Runnable fillColors = () -> {
            colors.getChildren().clear();
            if ("off".equals(l.colorMode)) return;
            colors.getChildren().add(colorField("Farbe 1", Colors.parseHex(l.color1), v -> fine(() -> l.color1 = v)));
            if ("solid".equals(l.colorMode)) return;
            colors.getChildren().add(colorField("Farbe 2", Colors.parseHex(l.color2), v -> fine(() -> l.color2 = v)));
            CheckBox third = new CheckBox("Dritte Farbe");
            third.setSelected(l.useColor3);
            third.setOnAction(e -> fine(() -> l.useColor3 = third.isSelected()));
            colors.getChildren().addAll(third, colorField("Farbe 3", Colors.parseHex(l.color3), v -> fine(() -> l.color3 = v)));
        };
        fillColors.run();

        CheckBox mirrorX = new CheckBox("Spiegeln links/rechts");
        mirrorX.setSelected(l.mirrorX);
        mirrorX.setOnAction(e -> fine(() -> l.mirrorX = mirrorX.isSelected()));
        CheckBox mirrorY = new CheckBox("Spiegeln oben/unten");
        mirrorY.setSelected(l.mirrorY);
        mirrorY.setOnAction(e -> fine(() -> l.mirrorY = mirrorY.isSelected()));
        CheckBox reverse = new CheckBox("Laufrichtung umkehren");
        reverse.setSelected(l.reverse);
        reverse.setOnAction(e -> fine(() -> l.reverse = reverse.isSelected()));

        Map<String, String> bands = new LinkedHashMap<>();
        bands.put("off", "Keine Musikreaktion");
        bands.putAll(de.knirpslarsi.ledstudio.audio.SoundSource.choices());
        if (!"off".equals(l.audioBand) && !bands.containsKey(l.audioBand)) {
            l.audioBand = de.knirpslarsi.ledstudio.audio.SoundSource.byId(l.audioBand).id; // ältere Werte übernehmen
        }
        VBox react = new VBox(10), custom = new VBox(10);
        reactMeter = new javafx.scene.control.ProgressBar(0);
        reactMeter.setMaxWidth(Double.MAX_VALUE);
        custom.getChildren().addAll(
                Ui.slider("Eigener Bereich von (Hz)", 40, 16000, l.audioFrom, 10, v -> fine(() -> l.audioFrom = v)),
                Ui.slider("Eigener Bereich bis (Hz)", 40, 16000, l.audioTo, 10, v -> fine(() -> l.audioTo = v)));
        Runnable showReact = () -> {
            boolean on = !"off".equals(l.audioBand);
            react.setVisible(on);
            react.setManaged(on);
            boolean own = "custom".equals(l.audioBand);
            custom.setVisible(own);
            custom.setManaged(own);
        };
        react.getChildren().addAll(
                Ui.field("Ausschlag gerade", reactMeter),
                custom,
                Ui.label("Was die Musik verändert", "field-label"),
                Ui.slider("Helligkeit", 0, 1, l.audioAmount, 0.01, v -> fine(() -> l.audioAmount = v)),
                muted("1: ohne Musik dunkel, mit Musik voll. 0: Helligkeit bleibt."),
                Ui.slider("Tempo antreiben", 0, 4, l.audioSpeed, 0.05, v -> fine(() -> l.audioSpeed = v)),
                muted("Der Effekt läuft bei Musik schneller, z. B. 2: bis zu dreimal so schnell."),
                Ui.slider("Farbton verschieben", 0, 1, l.audioHue, 0.01, v -> fine(() -> l.audioHue = v)),
                Ui.slider("Blasser (Sättigung)", 0, 1, l.audioSat, 0.01, v -> fine(() -> l.audioSat = v)),
                Ui.slider("Weiß aufblitzen", 0, 1, l.audioFlash, 0.01, v -> fine(() -> l.audioFlash = v)),
                Ui.label("Signal", "field-label"),
                Ui.slider("Verstärkung", 0.2, 4, l.audioBoost, 0.05, v -> fine(() -> l.audioBoost = v)),
                Ui.slider("Schwelle (Leises zählt nicht)", 0, 0.9, l.audioThreshold, 0.01, v -> fine(() -> l.audioThreshold = v)),
                Ui.slider("Kontrast", 0.3, 4, l.audioContrast, 0.05, v -> fine(() -> l.audioContrast = v)),
                Ui.slider("Anstieg (ms)", 1, 500, l.audioAttack, 1, v -> fine(() -> l.audioAttack = v)),
                Ui.slider("Abfall (ms)", 20, 2000, l.audioRelease, 5, v -> fine(() -> l.audioRelease = v)));
        showReact.run();
        if (meterTimer == null) {
            meterTimer = new javafx.animation.AnimationTimer() {
                private long last;

                @Override
                public void handle(long now) {
                    if (now - last < 50_000_000L) return;
                    last = now;
                    if (reactMeter != null && selected != null && reactMeter.isVisible()) {
                        reactMeter.setProgress(core.engine.layerLevel(selected.id));
                    }
                }
            };
            meterTimer.start();
        }

        fineForm.getChildren().addAll(
                Ui.section("Überblendung"),
                Ui.field("Mischen mit den Ebenen darunter", options(pairs("normal", "Normal (verdeckt)", "add", "Addieren (leuchtet zusammen)",
                        "max", "Aufhellen (hellerer Wert)", "multiply", "Multiplizieren (als Maske)", "subtract", "Abziehen"),
                        l.blend, v -> fine(() -> l.blend = v))),
                Ui.slider("Deckkraft", 0, 1, l.opacity, 0.01, v -> fine(() -> l.opacity = v)),
                Ui.section("Farbe"),
                Ui.field("Farben des Effekts ersetzen", options(pairs("off", "Nein, Farben des Effekts", "solid", "Eine Farbe",
                        "value", "Verlauf nach Helligkeit", "position", "Verlauf nach Position"), l.colorMode, v -> {
                    fine(() -> l.colorMode = v);
                    fillColors.run();
                })),
                colors,
                Ui.slider("Farbton verschieben", -0.5, 0.5, l.hueShift, 0.01, v -> fine(() -> l.hueShift = v)),
                Ui.slider("Sättigung", 0, 2, l.saturation, 0.01, v -> fine(() -> l.saturation = v)),
                Ui.slider("Helligkeit", 0, 2, l.brightness, 0.01, v -> fine(() -> l.brightness = v)),
                Ui.slider("Weichheit (Gamma)", 0.2, 4, l.gamma, 0.05, v -> fine(() -> l.gamma = v)),
                Ui.section("Zeit"),
                Ui.slider("Tempo dieser Ebene", 0, 5, l.speed, 0.01, v -> fine(() -> l.speed = v)),
                Ui.slider("Versatz (Sekunden)", 0, 30, l.phase, 0.05, v -> fine(() -> l.phase = v)),
                Ui.slider("Nachleuchten", 0, 2, l.trail, 0.01, v -> fine(() -> l.trail = v)),
                Ui.section("Bereich und Richtung"),
                Ui.field("Effekt läuft über", options(pairs("own", "Nur das Ziel dieser Ebene", "global", "Den ganzen Aufbau (Ziel zeigt den Ausschnitt)"),
                        l.scope, v -> fine(() -> l.scope = v))),
                Ui.field("Nur diese LEDs", options(pairs("all", "Alle", "rim", "Nur oben und unten am Rand", "sides", "Nur seitliche",
                        "odd", "Jede ungerade (1, 3, 5 …)", "even", "Jede gerade (2, 4, 6 …)"), l.ledFilter, v -> fine(() -> l.ledFilter = v))),
                reverse, mirrorX, mirrorY,
                Ui.slider("Drehen (°)", 0, 360, l.rotate, 1, v -> fine(() -> l.rotate = v)),
                Ui.slider("Muster-Größe", 0.1, 5, l.scale, 0.01, v -> fine(() -> l.scale = v)),
                Ui.section("Musikreaktion"),
                Ui.field("Reagiert auf", options(bands, l.audioBand, v -> {
                    fine(() -> l.audioBand = v);
                    showReact.run();
                })),
                react,
                wide(Ui.button("Feinschliff zurücksetzen", this::resetFine)));
    }

    private javafx.scene.control.ProgressBar reactMeter;
    private javafx.animation.AnimationTimer meterTimer;

    private static Label muted(String text) {
        Label l = Ui.label(text, "muted");
        l.setWrapText(true);
        return l;
    }

    private static Button wide(Button b) {
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private void resetFine() {
        Project.Layer d = new Project.Layer(), l = selected;
        l.blend = d.blend;
        l.opacity = d.opacity;
        l.colorMode = d.colorMode;
        l.hueShift = d.hueShift;
        l.saturation = d.saturation;
        l.brightness = d.brightness;
        l.gamma = d.gamma;
        l.speed = d.speed;
        l.phase = d.phase;
        l.trail = d.trail;
        l.scope = d.scope;
        l.ledFilter = d.ledFilter;
        l.reverse = d.reverse;
        l.mirrorX = d.mirrorX;
        l.mirrorY = d.mirrorY;
        l.rotate = d.rotate;
        l.scale = d.scale;
        l.audioBand = d.audioBand;
        l.audioAmount = d.audioAmount;
        l.audioSpeed = d.audioSpeed;
        l.audioHue = d.audioHue;
        l.audioSat = d.audioSat;
        l.audioFlash = d.audioFlash;
        l.audioFrom = d.audioFrom;
        l.audioTo = d.audioTo;
        l.audioThreshold = d.audioThreshold;
        l.audioContrast = d.audioContrast;
        l.audioBoost = d.audioBoost;
        l.audioAttack = d.audioAttack;
        l.audioRelease = d.audioRelease;
        layersChanged();
    }

    // ---- Vorlagen verwalten

    private Optional<String> askName(String header, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.setTitle("Vorlage");
        d.setHeaderText(header);
        d.getDialogPane().getStylesheets().add(getClass().getResource("/ledstudio/app.css").toExternalForm());
        return d.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    private void commit(String select) {
        core.project.current = select;
        core.engine.solo(null);
        selected = null;
        core.settingsChanged();
        refreshList();
        showPreset();
    }

    private void newPreset() {
        askName("Name der neuen Vorlage", "Neue Vorlage").ifPresent(name -> {
            String unique = core.project.uniquePresetName(name);
            core.project.presets.add(new Project.Preset(unique, "rotor", Map.of()));
            commit(unique);
        });
    }

    private void copyPreset() {
        Project.Preset p = preset();
        if (p == null) return;
        String unique = core.project.uniquePresetName(p.name + " Kopie");
        core.project.presets.add(core.project.presets.indexOf(p) + 1, p.copy(unique));
        commit(unique);
    }

    private void renamePreset() {
        Project.Preset p = preset();
        if (p == null) return;
        askName("Neuer Name", p.name).ifPresent(name -> {
            if (name.equals(p.name)) return;
            p.name = core.project.uniquePresetName(name);
            commit(p.name);
        });
    }

    private void deletePreset() {
        Project.Preset p = preset();
        if (p == null || core.project.presets.size() <= 1) return;
        if (!Ui.confirm(getScene().getWindow(), "Vorlage löschen", "„" + p.name + "“ wirklich löschen?")) return;
        int i = core.project.presets.indexOf(p);
        core.project.presets.remove(p);
        commit(core.project.presets.get(Math.min(i, core.project.presets.size() - 1)).name);
    }
}
