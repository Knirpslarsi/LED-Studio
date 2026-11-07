package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Erkannte OpenRGB-Geräte und ihre Zonen; von hier kommen die Bauteile ins Layout. */
final class DevicesPanel extends VBox {
    private final AppCore core;
    private final LayoutCanvas canvas;
    private final TreeView<Object> tree = new TreeView<>();
    private final VBox detail = new VBox(8);
    private final VBox issues = new VBox(6);
    private final Set<String> collapsed = new HashSet<>();

    private record ZoneRef(Controller device, Controller.Zone zone) {
    }

    DevicesPanel(AppCore core, LayoutCanvas canvas) {
        super(8);
        this.core = core;
        this.canvas = canvas;
        getStyleClass().add("panel-content");
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("muted");
                if (empty || item == null) {
                    setText(null);
                } else if (item instanceof Controller c) {
                    setText(c.displayName() + " (" + c.ledCount() + ")");
                    setTooltip(new javafx.scene.control.Tooltip(c.typeName() + ", " + c.ledCount() + " LEDs"));
                } else if (item instanceof ZoneRef z) {
                    long placed = core.project.fixtures.stream()
                            .filter(f -> f.deviceKey.equals(z.device.key()) && f.zoneName.equals(z.zone.name())).count();
                    setText(z.zone.name() + " · " + z.zone.ledCount() + (placed > 0 ? " · " + placed + "× im Layout" : ""));
                    if (z.zone.ledCount() == 0) getStyleClass().add("muted");
                }
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDetail());

        ScrollPane issueScroll = new ScrollPane(issues);
        issueScroll.setFitToWidth(true);
        issueScroll.setPrefHeight(170);
        issueScroll.setMinHeight(90);
        issueScroll.getStyleClass().add("panel");

        getChildren().addAll(
                Ui.section("Erkannte Geräte"),
                Ui.row(Ui.button("Neu einlesen", "Geräteliste von OpenRGB neu laden und Geräte neu suchen lassen", this::rescan),
                        Ui.button("Auto-Layout", "Alle Zonen als Bauteile anlegen", this::autoLayout)),
                tree, detail, Ui.section("Hinweise"), issueScroll);
        core.onDevices(this::rebuildTree);
        core.onLayout(() -> {
            tree.refresh();
            rebuildIssues();
        });
        rebuildTree();
    }

    private void rebuildTree() {
        TreeItem<Object> root = new TreeItem<>();
        Object selected = tree.getSelectionModel().getSelectedItem() == null ? null : tree.getSelectionModel().getSelectedItem().getValue();
        TreeItem<Object> reselect = null;
        for (Controller c : core.controllers()) {
            TreeItem<Object> device = new TreeItem<>(c);
            device.setExpanded(!collapsed.contains(c.key()));
            device.expandedProperty().addListener((o, a, open) -> {
                if (open) collapsed.remove(c.key());
                else collapsed.add(c.key());
            });
            for (Controller.Zone z : c.zones()) {
                TreeItem<Object> item = new TreeItem<>(new ZoneRef(c, z));
                device.getChildren().add(item);
                if (selected instanceof ZoneRef ref && ref.device.key().equals(c.key()) && ref.zone.name().equals(z.name())) reselect = item;
            }
            root.getChildren().add(device);
        }
        tree.setRoot(root);
        if (reselect != null) tree.getSelectionModel().select(reselect);
        rebuildIssues();
        showDetail();
    }

    private void showDetail() {
        detail.getChildren().clear();
        TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            detail.getChildren().add(Ui.label("Zone anklicken, um sie ins Layout zu holen.", "muted"));
            return;
        }
        if (item.getValue() instanceof Controller c) {
            Label info = Ui.label(c.vendor() + (c.serial().isBlank() ? "" : "\nSeriennummer: " + c.serial())
                    + (c.location().isBlank() ? "" : "\nAnschluss: " + c.location()) + "\nModi: " + String.join(", ", c.modes()), "muted");
            info.setWrapText(true);
            detail.getChildren().add(info);
            return;
        }
        if (!(item.getValue() instanceof ZoneRef ref)) return;
        Controller.Zone z = ref.zone;
        String kind = z.isMatrix() ? "Matrix " + z.matrixWidth() + " × " + z.matrixHeight()
                : z.resizable() ? "anpassbar " + z.ledsMin() + "–" + z.ledsMax() + " LEDs" : "feste Größe";
        detail.getChildren().add(Ui.label(z.ledCount() + " LEDs, " + kind, "muted"));
        if (!z.segments().isEmpty()) {
            detail.getChildren().add(Ui.label("Segmente: " + String.join(", ", z.segments().stream().map(Controller.Segment::name).toList()), "muted"));
        }
        if (z.ledCount() > 0 && !z.isMatrix()) {
            Spinner<Integer> perFan = new Spinner<>(1, Math.max(1, z.ledCount()), LayoutTools.suggestLedsPerFixture(ref.device, z));
            perFan.setEditable(true);
            perFan.setPrefWidth(84);
            detail.getChildren().add(Ui.row(Ui.label("LEDs pro Bauteil", "field-label"), perFan));
            int[] count = {perFan.getValue()};
            perFan.valueProperty().addListener((o, a, b) -> {
                if (b != null) count[0] = b;
            });
            detail.getChildren().add(Ui.primary("Zum Layout hinzufügen", () -> addToLayout(ref, count[0])));
        } else if (z.ledCount() > 0) {
            detail.getChildren().add(Ui.primary("Zum Layout hinzufügen", () -> addToLayout(ref, z.ledCount())));
        }
        if (z.segments().size() > 1) {
            detail.getChildren().add(Ui.button("Je Segment ein Bauteil", "Nutzt die Aufteilung, die schon in OpenRGB eingetragen ist", () -> {
                core.snapshot();
                double y = core.project.fixtures.stream().mapToDouble(f -> f.y + f.height / 2).max().orElse(-100) + 110;
                core.project.fixtures.addAll(LayoutTools.fromSegments(ref.device, z, 0, y));
                core.layoutChanged();
                canvas.fit();
            }));
        }
        if (z.resizable()) {
            Spinner<Integer> size = new Spinner<>(z.ledsMin(), z.ledsMax(), Math.max(z.ledsMin(), z.ledCount()));
            size.setEditable(true);
            size.setPrefWidth(84);
            detail.getChildren().add(Ui.row(Ui.label("Zonengröße", "field-label"), size,
                    Ui.button("In OpenRGB ändern", "Stellt ein, wie viele LEDs an dieser Zone hängen", () -> resize(ref, size.getValue()))));
        }
        LedBackend b = core.connection.backend();
        if (b != null && b.supportsSegments() && !z.isMatrix() && z.ledCount() > 0) {
            detail.getChildren().add(Ui.button("Aufteilung als Segmente in OpenRGB schreiben",
                    "Trägt jedes Bauteil dieser Zone als eigenes Segment in OpenRGB ein", () -> writeSegments(ref)));
        }
    }

    private void addToLayout(ZoneRef ref, int perFixture) {
        core.snapshot();
        double y = core.project.fixtures.stream().mapToDouble(f -> f.y + f.height / 2).max().orElse(-100) + 110;
        List<Fixture> created = LayoutTools.fromZone(ref.device, ref.zone, perFixture, 0, y);
        core.project.fixtures.addAll(created);
        core.layoutChanged();
        canvas.selection.clear();
        created.forEach(f -> canvas.selection.add(f.id));
        canvas.fit();
        canvas.select(created.isEmpty() ? null : created.get(0), true);
    }

    private void autoLayout() {
        List<Fixture> created = LayoutTools.autoLayout(core.controllers());
        if (created.isEmpty()) {
            Ui.info(getScene().getWindow(), "Auto-Layout", "Noch keine Geräte erkannt. Läuft OpenRGB?");
            return;
        }
        if (!core.project.fixtures.isEmpty()
                && !Ui.confirm(getScene().getWindow(), "Auto-Layout", "Das ersetzt dein aktuelles Layout durch alle erkannten Zonen. Rückgängig geht mit Strg+Z.")) {
            return;
        }
        core.snapshot();
        core.project.fixtures.clear();
        core.project.fixtures.addAll(created);
        canvas.select(null, false);
        core.layoutChanged();
        canvas.fit();
    }

    private void rescan() {
        LedBackend b = core.connection.backend();
        if (b == null) return;
        core.background(() -> {
            b.rescan();
            Thread.sleep(800);
            core.connection.refreshNow();
        }, null, msg -> Ui.error(getScene().getWindow(), "Neu einlesen", msg));
    }

    private void resize(ZoneRef ref, int size) {
        LedBackend b = core.connection.backend();
        if (b == null) return;
        if (!Ui.confirm(getScene().getWindow(), "Zonengröße ändern",
                "OpenRGB speichert für „" + ref.zone.name() + "“ dauerhaft " + size + " statt " + ref.zone.ledCount() + " LEDs.\n"
                        + "Das gilt auch für andere Programme, die OpenRGB nutzen.")) return;
        core.background(() -> {
            b.resizeZone(ref.device.index(), ref.zone.index(), size);
            Thread.sleep(300);
            core.connection.refreshNow();
        }, null, msg -> Ui.error(getScene().getWindow(), "Zonengröße ändern", msg));
    }

    private void writeSegments(ZoneRef ref) {
        LedBackend b = core.connection.backend();
        if (b == null) return;
        List<Fixture> parts = new ArrayList<>();
        Set<Integer> starts = new HashSet<>();
        core.project.fixtures.stream()
                .filter(f -> f.deviceKey.equals(ref.device.key()) && f.zoneName.equals(ref.zone.name()))
                .sorted(Comparator.comparingInt(f -> f.start))
                .forEach(f -> {
                    if (starts.add(f.start)) parts.add(f); // Splitter-Bauteile teilen sich ein Segment
                });
        if (parts.isEmpty()) {
            Ui.info(getScene().getWindow(), "Segmente", "Diese Zone hat noch keine Bauteile im Layout.");
            return;
        }
        if (!Ui.confirm(getScene().getWindow(), "Segmente schreiben",
                "OpenRGB ersetzt die Segmente von „" + ref.zone.name() + "“ dauerhaft durch " + parts.size() + " neue:\n"
                        + String.join(", ", parts.stream().map(f -> f.name).toList()))) return;
        core.background(() -> {
            b.clearSegments(ref.device.index(), ref.zone.index());
            for (Fixture f : parts) {
                int count = Math.min(f.count, ref.zone.ledCount() - f.start);
                if (count > 0) b.addSegment(ref.device.index(), ref.zone.index(), f.name, f.start, count);
            }
            Thread.sleep(300);
            core.connection.refreshNow();
        }, null, msg -> Ui.error(getScene().getWindow(), "Segmente schreiben", msg));
    }

    private void rebuildIssues() {
        issues.getChildren().clear();
        List<LayoutTools.Issue> list = core.issues();
        if (list.isEmpty()) {
            issues.getChildren().add(Ui.label("Keine Duplikate oder Probleme gefunden.", "muted"));
        }
        for (LayoutTools.Issue i : list) {
            Label l = Ui.label(i.title() + "\n" + i.detail(), i.kind() == LayoutTools.Issue.Kind.UNMAPPED ? "muted" : "warning");
            l.setWrapText(true);
            issues.getChildren().add(l);
        }
        Label note = Ui.label("Lüfter an einem ARGB-Splitter meldet keine Hardware. Finde sie mit „Durchlauf“: leuchten zwei Lüfter zusammen, lege das zweite als Bauteil auf denselben Adressen an.", "muted");
        note.setWrapText(true);
        issues.getChildren().add(note);
    }
}
