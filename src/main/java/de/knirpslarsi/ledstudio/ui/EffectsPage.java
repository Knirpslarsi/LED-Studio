package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.ParamSpec;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.model.Project;
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
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Vorlagen wählen, Effekt live in der Vorschau sehen, Parameter einstellen. */
final class EffectsPage extends SplitPane {
    private final AppCore core;
    private final ListView<String> list = new ListView<>();
    private final VBox form = new VBox(10);
    private final Label title = Ui.label("", "page-title");
    private final Label blurb = Ui.label("", "muted");
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
                core.selectPreset(b);
                showForm();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox left = new VBox(8, Ui.section("Vorlagen"), list,
                Ui.row(Ui.button("Neu", this::newPreset), Ui.button("Kopieren", this::copyPreset)),
                Ui.row(Ui.button("Umbenennen", this::renamePreset), Ui.button("Löschen", this::deletePreset)));
        left.getStyleClass().add("panel-content");

        LayoutCanvas preview = new LayoutCanvas(core, false);
        VBox.setVgrow(preview, Priority.ALWAYS);
        blurb.setWrapText(true);
        VBox middle = new VBox(6, title, blurb, preview, Ui.row(Ui.button("Layout bearbeiten", goToLayout),
                Ui.label("Vorschau zeigt, was deine LEDs gerade tun.", "muted")));
        middle.getStyleClass().add("panel-content");

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel");
        form.getStyleClass().add("panel-content");

        getItems().addAll(left, middle, scroll);
        setDividerPositions(0.2, 0.72);
        SplitPane.setResizableWithParent(left, false);
        SplitPane.setResizableWithParent(scroll, false);
        left.setMinWidth(190);
        scroll.setMinWidth(280);

        refreshList();
        showForm();
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

    void externalChange() {
        refreshList();
        showForm();
    }

    private void showForm() {
        form.getChildren().clear();
        Project.Preset preset = core.project.preset(core.project.current);
        if (preset == null) {
            title.setText("Aus");
            blurb.setText("Alle Bauteile im Layout bleiben dunkel.");
            return;
        }
        Effect effect = Effects.create(preset.effect);
        title.setText(preset.name);
        blurb.setText(effect == null ? "Effekt „" + preset.effect + "“ nicht gefunden." : effect.blurb());

        Map<String, Effect> catalog = new LinkedHashMap<>();
        Effects.catalog().forEach(e -> catalog.put(e.title(), e));
        ComboBox<String> chooser = new ComboBox<>(FXCollections.observableArrayList(catalog.keySet()));
        chooser.setMaxWidth(Double.MAX_VALUE);
        if (effect != null) chooser.setValue(effect.title());
        chooser.setOnAction(e -> {
            Effect chosen = catalog.get(chooser.getValue());
            if (chosen != null && !chosen.id().equals(preset.effect)) {
                preset.effect = chosen.id();
                preset.params = new LinkedHashMap<>();
                core.settingsChanged();
                showForm();
            }
        });
        form.getChildren().addAll(Ui.section("Effekt"), chooser);
        if (effect == null) return;

        form.getChildren().add(Ui.section("Einstellungen"));
        Params values = new Params(effect.params(), preset.params);
        for (ParamSpec spec : effect.params()) form.getChildren().add(control(spec, values, preset));
        Button reset = Ui.button("Alle Einstellungen zurücksetzen", () -> {
            preset.params = new LinkedHashMap<>();
            core.settingsChanged();
            showForm();
        });
        reset.setMaxWidth(Double.MAX_VALUE);
        form.getChildren().add(reset);
    }

    private void set(Project.Preset preset, String name, Object value) {
        preset.params.put(name, value);
        core.settingsChanged();
    }

    private Node control(ParamSpec s, Params values, Project.Preset preset) {
        Node n = switch (s.type) {
            case FLOAT, INT -> Ui.slider(s.label, s.min, s.max, values.d(s.name), s.step, v -> set(preset, s.name, v));
            case HUE -> hue(s, values, preset);
            case BOOL -> {
                CheckBox c = new CheckBox(s.label);
                c.setSelected(values.b(s.name));
                c.selectedProperty().addListener((o, a, b) -> set(preset, s.name, b ? 1 : 0));
                yield c;
            }
            case COLOR -> color(s, values, preset);
            case CHOICE -> choice(s, values, preset);
        };
        if (s.help == null) return n;
        Label help = Ui.label(s.help, "muted");
        help.setWrapText(true);
        return new VBox(2, n, help);
    }

    private Node hue(ParamSpec s, Params values, Project.Preset preset) {
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
            set(preset, s.name, Math.round(b.doubleValue() * 1000) / 1000.0);
        });
        HBox head = new HBox(6, Ui.label(s.label, "field-label"), Ui.grow(), swatch);
        return new VBox(3, head, slider);
    }

    private Node color(ParamSpec s, Params values, Project.Preset preset) {
        int value = values.color(s.name);
        ColorPicker picker = new ColorPicker(Ui.fx(value));
        picker.setPrefWidth(46);
        picker.getStyleClass().add("button");
        TextField hex = new TextField(Colors.toHex(value));
        hex.setPrefColumnCount(7);
        HBox.setHgrow(hex, Priority.ALWAYS);
        picker.setOnAction(e -> {
            String text = Colors.toHex(Ui.rgb(picker.getValue()));
            hex.setText(text);
            set(preset, s.name, text);
        });
        Runnable commit = () -> {
            try {
                int c = Colors.parseHex(hex.getText());
                picker.setValue(Ui.fx(c));
                hex.setText(Colors.toHex(c));
                set(preset, s.name, Colors.toHex(c));
            } catch (RuntimeException ex) {
                hex.setText(Colors.toHex(Ui.rgb(picker.getValue()))); // ungültige Werte zurücksetzen
            }
        };
        hex.setOnAction(e -> commit.run());
        hex.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) commit.run();
        });
        return Ui.field(s.label, new HBox(6, picker, hex));
    }

    private Node choice(ParamSpec s, Params values, Project.Preset preset) {
        List<String> keys = new ArrayList<>(s.choices.keySet());
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(s.choices.values()));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setValue(s.choices.getOrDefault(values.s(s.name), s.choices.values().iterator().next()));
        box.setOnAction(e -> {
            int i = box.getSelectionModel().getSelectedIndex();
            if (i >= 0) set(preset, s.name, keys.get(i));
        });
        return Ui.field(s.label, box);
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
        core.settingsChanged();
        refreshList();
        showForm();
    }

    private void newPreset() {
        askName("Name der neuen Vorlage", "Neue Vorlage").ifPresent(name -> {
            String unique = core.project.uniquePresetName(name);
            core.project.presets.add(new Project.Preset(unique, "rotor", Map.of()));
            commit(unique);
        });
    }

    private void copyPreset() {
        Project.Preset p = core.project.preset(core.project.current);
        if (p == null) return;
        String unique = core.project.uniquePresetName(p.name + " Kopie");
        core.project.presets.add(core.project.presets.indexOf(p) + 1, new Project.Preset(unique, p.effect, p.params));
        commit(unique);
    }

    private void renamePreset() {
        Project.Preset p = core.project.preset(core.project.current);
        if (p == null) return;
        askName("Neuer Name", p.name).ifPresent(name -> {
            if (name.equals(p.name)) return;
            p.name = core.project.uniquePresetName(name);
            commit(p.name);
        });
    }

    private void deletePreset() {
        Project.Preset p = core.project.preset(core.project.current);
        if (p == null || core.project.presets.size() <= 1) return;
        if (!Ui.confirm(getScene().getWindow(), "Vorlage löschen", "„" + p.name + "“ wirklich löschen?")) return;
        int i = core.project.presets.indexOf(p);
        core.project.presets.remove(p);
        commit(core.project.presets.get(Math.min(i, core.project.presets.size() - 1)).name);
    }
}
