package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/** Eigenschaften des gewählten Bauteils: Adresse, Form und Ausrichtung. */
final class Inspector extends ScrollPane {
    private final AppCore core;
    private final LayoutCanvas canvas;
    private final VBox box = new VBox(10);
    private boolean snapshotTaken;

    Inspector(AppCore core, LayoutCanvas canvas) {
        this.core = core;
        this.canvas = canvas;
        setContent(box);
        setFitToWidth(true);
        getStyleClass().add("panel");
        box.getStyleClass().add("panel-content");
        rebuild();
        canvas.onSelection(this::rebuild);
        core.onDevices(this::rebuild);
    }

    private void change(Runnable r) {
        if (!snapshotTaken) {
            core.snapshot();
            snapshotTaken = true;
        }
        r.run();
        core.layoutChanged();
    }

    void rebuild() {
        snapshotTaken = false;
        box.getChildren().clear();
        List<Fixture> sel = canvas.selectedFixtures();
        if (sel.isEmpty()) {
            Label hint = Ui.label("Klicke ein Bauteil an, um Adresse, Form und Ausrichtung zu ändern.", "muted");
            hint.setWrapText(true);
            box.getChildren().addAll(Ui.section("Bauteil"), hint);
            box.getChildren().add(helpBlock());
            return;
        }
        if (sel.size() > 1) {
            box.getChildren().add(multi(sel));
            return;
        }
        single(sel.get(0));
    }

    private Node helpBlock() {
        VBox v = new VBox(4);
        for (String line : new String[]{
                "Ziehen: verschieben (Alt: ohne Raster)", "Griffe: drehen und Größe ändern",
                "Rahmen ziehen oder Umschalt: Mehrfachauswahl", "Mausrad: zoomen, rechte Maustaste: Ansicht verschieben",
                "LED-Modus: einzelne LEDs ziehen", "LED-Modus + Strg-Klick: LED aus-/einschalten",
                "Entf, Strg+Z/Y, Strg+D, Pfeiltasten"}) {
            Label l = Ui.label(line, "muted");
            l.setWrapText(true);
            v.getChildren().add(l);
        }
        return v;
    }

    private Node multi(List<Fixture> sel) {
        VBox v = new VBox(8, Ui.section(sel.size() + " Bauteile gewählt"));
        v.getChildren().addAll(
                Ui.button("Links ausrichten", () -> {
                    double min = sel.stream().mapToDouble(s -> s.x).min().orElse(0);
                    align(sel, f -> f.x = min);
                }),
                Ui.button("Oben ausrichten", () -> {
                    double min = sel.stream().mapToDouble(s -> s.y).min().orElse(0);
                    align(sel, f -> f.y = min);
                }),
                Ui.button("Waagerecht verteilen", () -> distribute(sel, true)),
                Ui.button("Senkrecht verteilen", () -> distribute(sel, false)),
                Ui.button("Kette nach Position sortieren", () -> {
                    core.snapshot();
                    LayoutTools.sortChainByPosition(core.project.fixtures);
                    core.layoutChanged();
                }),
                Ui.button("Entfernen", canvas::deleteSelected));
        for (Node n : v.getChildren()) if (n instanceof javafx.scene.control.Button b) b.setMaxWidth(Double.MAX_VALUE);
        return v;
    }

    private void align(List<Fixture> sel, Consumer<Fixture> action) {
        core.snapshot();
        sel.forEach(action);
        core.layoutChanged();
    }

    private void distribute(List<Fixture> sel, boolean horizontal) {
        if (sel.size() < 3) return;
        core.snapshot();
        List<Fixture> sorted = new ArrayList<>(sel);
        sorted.sort((a, b) -> Double.compare(horizontal ? a.x : a.y, horizontal ? b.x : b.y));
        double first = horizontal ? sorted.get(0).x : sorted.get(0).y;
        double last = horizontal ? sorted.get(sorted.size() - 1).x : sorted.get(sorted.size() - 1).y;
        for (int i = 0; i < sorted.size(); i++) {
            double v = first + (last - first) * i / (sorted.size() - 1);
            if (horizontal) sorted.get(i).x = v;
            else sorted.get(i).y = v;
        }
        core.layoutChanged();
    }

    private void single(Fixture f) {
        TextField name = new TextField(f.name);
        name.textProperty().addListener((o, a, b) -> change(() -> f.name = b));
        box.getChildren().addAll(Ui.section("Bauteil"), name);

        // ---- Adresse
        box.getChildren().add(Ui.section("LED-Adresse"));
        List<Controller> devices = core.controllers();
        ComboBox<String> device = new ComboBox<>();
        device.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> zone = new ComboBox<>();
        zone.setMaxWidth(Double.MAX_VALUE);
        String[] keys = devices.stream().map(Controller::key).toArray(String[]::new);
        device.setItems(FXCollections.observableArrayList(devices.stream().map(Controller::displayName).toList()));
        int currentDevice = Arrays.asList(keys).indexOf(f.deviceKey);
        if (currentDevice >= 0) device.getSelectionModel().select(currentDevice);
        Runnable fillZones = () -> {
            int d = device.getSelectionModel().getSelectedIndex();
            zone.setItems(FXCollections.observableArrayList(d < 0 ? List.of()
                    : devices.get(d).zones().stream().map(Controller.Zone::name).toList()));
            zone.getSelectionModel().select(f.zoneName);
        };
        fillZones.run();
        device.setOnAction(e -> {
            int d = device.getSelectionModel().getSelectedIndex();
            if (d < 0 || keys[d].equals(f.deviceKey)) return;
            change(() -> {
                f.deviceKey = keys[d];
                f.zoneName = devices.get(d).zones().isEmpty() ? "" : devices.get(d).zones().get(0).name();
                f.start = 0;
            });
            fillZones.run();
        });
        zone.setOnAction(e -> {
            String z = zone.getSelectionModel().getSelectedItem();
            if (z != null && !z.equals(f.zoneName)) change(() -> f.zoneName = z);
        });
        box.getChildren().addAll(Ui.field("Gerät", device), Ui.field("Zone", zone));
        GridPane addr = grid();
        addInt(addr, 0, 0, "Erste LED", f.start + 1, 1, 2000, v -> change(() -> f.start = v - 1));
        addInt(addr, 1, 0, "LEDs", f.count, 1, 2000, v -> change(() -> f.count = v));
        box.getChildren().add(addr);
        for (LayoutTools.Issue i : core.issues()) {
            if (i.fixtureIds().contains(f.id)) {
                javafx.scene.control.Label l = Ui.label(i.title() + "\n" + i.detail(), "warning");
                l.setWrapText(true);
                box.getChildren().add(l);
            }
        }

        // ---- Form
        box.getChildren().add(Ui.section("Form"));
        ComboBox<Fixture.Shape> shape = new ComboBox<>(FXCollections.observableArrayList(Fixture.Shape.values()));
        shape.setMaxWidth(Double.MAX_VALUE);
        shape.setValue(f.shape);
        shape.setOnAction(e -> {
            Fixture.Shape s = shape.getValue();
            if (s == f.shape) return;
            change(() -> {
                if (s == Fixture.Shape.FREE) LayoutCanvas.makeFree(f);
                else f.shape = s;
                if (s == Fixture.Shape.LINE) f.height = 20;
                else if (f.height <= 20) f.height = f.width;
            });
            rebuild();
        });
        box.getChildren().add(shape);
        GridPane size = grid();
        addDouble(size, 0, 0, "Breite (mm)", f.width, 10, 2000, 5, v -> change(() -> f.width = v));
        addDouble(size, 1, 0, "Höhe (mm)", f.height, 5, 2000, 5, v -> change(() -> f.height = v));
        int row = 1;
        if (f.shape == Fixture.Shape.DUAL_RING) addInt(size, 0, row++, "LEDs innen", f.innerCount, 0, 2000, v -> change(() -> f.innerCount = v));
        if (f.shape == Fixture.Shape.SQUARE) addDouble(size, 0, row++, "Eckenradius", f.corner, 0, 0.5, 0.05, v -> change(() -> f.corner = v));
        if (f.shape == Fixture.Shape.ARC) addDouble(size, 0, row++, "Winkel (°)", f.arcDegrees, 10, 360, 5, v -> change(() -> f.arcDegrees = v));
        if (f.shape == Fixture.Shape.GRID) {
            addInt(size, 0, row, "Spalten", f.cols, 1, 200, v -> change(() -> f.cols = v));
            CheckBox serp = check("Schlangenlinie", f.serpentine, v -> change(() -> f.serpentine = v));
            size.add(serp, 1, row * 2 + 1);
        }
        box.getChildren().add(size);

        // ---- Ausrichtung
        box.getChildren().add(Ui.section("Ausrichtung"));
        GridPane orient = grid();
        addDouble(orient, 0, 0, "Drehung (°)", f.rotation, 0, 359.5, 5, v -> change(() -> f.rotation = v));
        addDouble(orient, 1, 0, "Start-LED bei (°)", f.startAngle, 0, 359.5, 5, v -> change(() -> f.startAngle = v));
        box.getChildren().add(orient);
        Label startHelp = Ui.label("Start-LED: 0° = rechts, 90° = unten, 180° = links, 270° = oben", "muted");
        startHelp.setWrapText(true);
        box.getChildren().add(startHelp);
        box.getChildren().add(check("LEDs laufen andersherum", f.reverse, v -> change(() -> f.reverse = v)));
        box.getChildren().add(Ui.row(
                check("Spiegeln X", f.flipX, v -> change(() -> f.flipX = v)),
                check("Spiegeln Y", f.flipY, v -> change(() -> f.flipY = v))));

        // ---- Ausgabe
        box.getChildren().add(Ui.section("Ausgabe"));
        ComboBox<String> order = new ComboBox<>(FXCollections.observableArrayList("RGB", "RBG", "GRB", "GBR", "BRG", "BGR"));
        order.setValue(f.colorOrder);
        order.setOnAction(e -> change(() -> f.colorOrder = order.getValue()));
        box.getChildren().add(Ui.field("Farbreihenfolge, falls vertauscht", order));
        box.getChildren().add(Ui.slider("Helligkeit dieses Bauteils", 0, 1, f.brightness, 0.01, v -> change(() -> f.brightness = v)));
        TextField skip = new TextField(joinSkip(f));
        skip.setPromptText("z. B. 3, 7");
        skip.textProperty().addListener((o, a, b) -> change(() -> f.skip = parseSkip(b)));
        box.getChildren().add(Ui.field("Diese LEDs auslassen", skip));

        // ---- Aktionen
        int index = core.project.fixtures.indexOf(f);
        box.getChildren().add(Ui.section("Kette: Position " + (index + 1) + " von " + core.project.fixtures.size()));
        box.getChildren().add(Ui.row(
                Ui.button("▲ früher", "Effekte laufen früher über dieses Bauteil", () -> moveInChain(f, -1)),
                Ui.button("▼ später", "Effekte laufen später über dieses Bauteil", () -> moveInChain(f, 1))));
        box.getChildren().add(Ui.section("Aktionen"));
        box.getChildren().addAll(
                wide(Ui.button("Real aufleuchten lassen", () -> canvas.identifyFixture(f))),
                wide(Ui.button("Kopie mit nächsten Adressen", () -> canvas.duplicate(false))),
                wide(Ui.button("Zweites Bauteil auf denselben Adressen", "Für Lüfter am ARGB-Splitter, die immer gleich leuchten", () -> canvas.duplicate(true))),
                wide(Ui.button("Entfernen", canvas::deleteSelected)));
    }

    private void moveInChain(Fixture f, int delta) {
        List<Fixture> list = core.project.fixtures;
        int i = list.indexOf(f), j = i + delta;
        if (j < 0 || j >= list.size()) return;
        core.snapshot();
        list.set(i, list.get(j));
        list.set(j, f);
        core.layoutChanged();
        rebuild();
    }

    private static javafx.scene.control.Button wide(javafx.scene.control.Button b) {
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private static String joinSkip(Fixture f) {
        return String.join(", ", f.skip.stream().map(String::valueOf).toList());
    }

    private static List<Integer> parseSkip(String text) {
        List<Integer> out = new ArrayList<>();
        for (String s : text.split("[,; ]+")) {
            if (s.matches("\\d{1,5}")) out.add(Integer.parseInt(s));
        }
        return out;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(3);
        javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
        c.setPercentWidth(50);
        c.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c, c);
        return g;
    }

    private static CheckBox check(String label, boolean value, Consumer<Boolean> onChange) {
        CheckBox c = new CheckBox(label);
        c.setSelected(value);
        c.selectedProperty().addListener((o, a, b) -> onChange.accept(b));
        return c;
    }

    private static void addInt(GridPane g, int col, int row, String label, int value, int min, int max, IntConsumer onChange) {
        Spinner<Integer> s = new Spinner<>(min, max, value);
        s.setEditable(true);
        s.setMaxWidth(Double.MAX_VALUE);
        s.valueProperty().addListener((o, a, b) -> {
            if (b != null) onChange.accept(b);
        });
        commitOnBlur(s);
        g.add(Ui.label(label, "field-label"), col, row * 2);
        g.add(s, col, row * 2 + 1);
    }

    private static void addDouble(GridPane g, int col, int row, String label, double value, double min, double max, double step, DoubleConsumer onChange) {
        Spinner<Double> s = new Spinner<>(min, max, value, step);
        s.setEditable(true);
        s.setMaxWidth(Double.MAX_VALUE);
        s.valueProperty().addListener((o, a, b) -> {
            if (b != null) onChange.accept(b);
        });
        commitOnBlur(s);
        g.add(Ui.label(label, "field-label"), col, row * 2);
        g.add(s, col, row * 2 + 1);
    }

    /** Getippte Werte auch ohne Enter übernehmen; Unsinn wird zurückgesetzt. */
    private static <T> void commitOnBlur(Spinner<T> s) {
        s.focusedProperty().addListener((o, a, focused) -> {
            if (focused) return;
            try {
                s.increment(0);
                T value = s.getValueFactory().getConverter().fromString(s.getEditor().getText());
                if (value != null) s.getValueFactory().setValue(value);
            } catch (RuntimeException e) {
                s.getEditor().setText(s.getValueFactory().getConverter().toString(s.getValue()));
            }
        });
    }
}
