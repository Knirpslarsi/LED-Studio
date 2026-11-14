package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.audio.Song;
import de.knirpslarsi.ledstudio.audio.SongPlayer;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.ParamSpec;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.Targets;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.model.Show;
import de.knirpslarsi.ledstudio.model.ShowEdit;
import de.knirpslarsi.ledstudio.util.Colors;
import javafx.animation.AnimationTimer;
import javafx.collections.FXCollections;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Timecode: eine Lichtshow auf einer Zeitleiste passend zum Song. Spuren zielen auf alles, Geräte, Lüfter oder
 * einzelne LEDs; Clips darauf laufen zu festen Zeiten. Gespeichert wird als .ledshow-Datei.
 */
final class ShowPage extends BorderPane {
    private static final Logger LOG = Logger.getLogger("Show");

    private final AppCore core;
    private final SongPlayer player = new SongPlayer();
    private Show show = new Show();
    private Path file;
    private boolean dirty;
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;

    private final TimelineCanvas timeline;
    private final ScrollBar hbar = new ScrollBar();
    private final VBox inspector = new VBox(10);
    private final Label title = Ui.label("Show", "page-title"), songLabel = Ui.label("Kein Song", "muted"), timeLabel = Ui.label("0:00,0", "value");
    private final ToggleButton playButton = new ToggleButton("▶  Abspielen");
    private final CheckBox onLeds = new CheckBox("Auf den LEDs zeigen");
    private final CheckBox record = new CheckBox("Aufnehmen mit Tasten 1–9");
    private final TextField lengthField = new TextField();
    private LedPicker picker;
    /** Spuren, deren Taste gerade gedrückt ist, und ihr wachsender Clip. */
    private final Map<Integer, Show.Clip> held = new HashMap<>();
    private boolean engineShowing;
    private List<ShowEdit.Copied> clipboard = new ArrayList<>();
    /** Einstellungen des Sequenz-Assistenten, bleiben zwischen Spuren erhalten. */
    private final de.knirpslarsi.ledstudio.model.Sequence.Spec seq = new de.knirpslarsi.ledstudio.model.Sequence.Spec();
    private String seqStep = "0.5";
    private final CheckBox rerollCopies = new CheckBox("Kopien neu würfeln");
    private final java.util.Random dice = new java.util.Random();

    ShowPage(AppCore core) {
        this.core = core;
        timeline = new TimelineCanvas(player::time, this::seek, this::selected, this::changed);
        timeline.onView = this::syncScrollbar;
        timeline.onContext = this::contextMenu;
        timeline.setFocusTraversable(true);

        // Kopfzeile
        Button newShow = Ui.button("Neu", this::newShow);
        Button open = Ui.button("Öffnen …", this::open);
        Button save = Ui.button("Speichern", this::save);
        Button saveAs = Ui.button("Speichern unter …", this::saveAs);
        Button loadSong = Ui.primary("Song laden …", this::chooseSong);
        HBox head = Ui.row(title, Ui.grow(), newShow, open, save, saveAs, loadSong);
        playButton.setMinWidth(118);
        timeLabel.setMinWidth(64);
        songLabel.setMinWidth(Region.USE_PREF_SIZE);
        head.getStyleClass().add("toolbar-row");

        playButton.setOnAction(e -> togglePlay());
        Button stop = Ui.button("Stopp", "Stopp, zurück zum Anfang", () -> {
            player.stop();
            playButton.setSelected(false);
            playButton.setText("▶  Abspielen");
            refreshEngine();
        });
        Button addTrack = Ui.button("+ Spur", this::addTrack);
        javafx.scene.control.MenuButton snap = new javafx.scene.control.MenuButton("Einrasten");
        snap.getItems().addAll(
                snapItem("Einrasten an", () -> timeline.snap.enabled, v -> timeline.snap.enabled = v),
                new javafx.scene.control.SeparatorMenuItem(),
                snapItem("an Beats", () -> timeline.snap.beats, v -> timeline.snap.beats = v),
                snapItem("an Markierungen", () -> timeline.snap.markers, v -> timeline.snap.markers = v),
                snapItem("an Clip-Anfängen und -Enden", () -> timeline.snap.clips, v -> timeline.snap.clips = v),
                snapItem("am Abspielkopf", () -> timeline.snap.playhead, v -> timeline.snap.playhead = v));
        Button marker = Ui.button("+ Markierung", "Markierung am Abspielkopf setzen (Taste M)", () -> addMarker(player.time()));
        Slider volume = new Slider(0, 1, core.project.showVolume);
        volume.setPrefWidth(110);
        player.setVolume(core.project.showVolume);
        volume.valueProperty().addListener((o, a, b) -> player.setVolume(b.doubleValue()));
        volume.setOnMouseReleased(e -> {
            core.project.showVolume = volume.getValue();
            core.project.save();
        });
        onLeds.setSelected(true);
        onLeds.selectedProperty().addListener((o, a, b) -> refreshEngine());
        Slider zoom = new Slider(8, 600, timeline.pxPerSec);
        zoom.setPrefWidth(140);
        zoom.valueProperty().addListener((o, a, b) -> {
            timeline.pxPerSec = b.doubleValue();
            timeline.redraw();
            syncScrollbar();
        });
        lengthField.setPrefColumnCount(4);
        lengthField.setOnAction(e -> applyLength());
        lengthField.focusedProperty().addListener((o, a, f) -> {
            if (!f) applyLength();
        });
        stop.setMinWidth(Region.USE_PREF_SIZE);
        HBox transport = Ui.row(playButton, stop, timeLabel, Ui.label("Lautstärke", "muted"), volume, Ui.grow(),
                Ui.label("Zoom", "muted"), zoom);
        transport.getStyleClass().add("toolbar-row");
        rerollCopies.setSelected(true);
        rerollCopies.setTooltip(new javafx.scene.control.Tooltip("Beim Duplizieren und Einfügen bekommen Kopien neue zufällige LEDs "
                + "und einen neuen Zufall, damit nicht jedes Mal dasselbe leuchtet."));
        HBox edit = Ui.row(addTrack, marker, snap, record, rerollCopies, Ui.grow(), onLeds);
        edit.getStyleClass().add("toolbar-row");
        for (Node n : List.of(addTrack, marker, snap, record, onLeds, stop, rerollCopies)) {
            if (n instanceof javafx.scene.control.Control c) c.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox songRow = Ui.row(songLabel, Ui.grow(), Ui.label("Länge ohne Song (s)", "muted"), lengthField);
        songRow.getStyleClass().add("toolbar-row");

        ScrollPane lanes = new ScrollPane(timeline);
        lanes.setFitToWidth(true);
        lanes.getStyleClass().add("panel");
        lanes.setOnKeyPressed(this::keyPressed);
        hbar.setOrientation(Orientation.HORIZONTAL);
        hbar.valueProperty().addListener((o, a, b) -> {
            if (Math.abs(timeline.scroll - b.doubleValue()) > 1e-6) {
                timeline.scroll = b.doubleValue();
                timeline.redraw();
            }
        });
        Label hint = Ui.label("Doppelklick: Clip anlegen · Ziehen: verschieben, Ränder: Länge · Rahmen aufziehen oder Strg+Klick: "
                + "mehrere markieren · Rechtsklick: Menü · Alt: ohne Einrasten · Entf: löschen · Strg+C/X/V: kopieren, ausschneiden, "
                + "einfügen · Strg+D: duplizieren · Strg+A: alles · ← →: Beat, Umschalt: Takt, Strg: markierte Clips schieben · "
                + "Bild ↑↓: Markierungen · M: Markierung · Leertaste: Abspielen · Strg+Mausrad: zoomen · Strg+Z/Y · Strg+S", "muted");
        hint.setWrapText(true);
        VBox center = new VBox(0, lanes, hbar, hint);
        VBox.setVgrow(lanes, Priority.ALWAYS);
        hint.setStyle("-fx-padding: 6 10;");

        inspector.getStyleClass().add("panel-content");
        ScrollPane side = new ScrollPane(inspector);
        side.setFitToWidth(true);
        side.setPrefWidth(330);
        side.getStyleClass().add("panel");

        setTop(new VBox(head, songRow, transport, edit));
        setCenter(center);
        setRight(side);
        addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        addEventFilter(KeyEvent.KEY_RELEASED, this::keyReleased);

        visibleProperty().addListener((o, a, v) -> refreshEngine());
        new AnimationTimer() {
            private long last;

            @Override
            public void handle(long now) {
                if (!isVisible() || now - last < 33_000_000L) return;
                last = now;
                tick();
            }
        }.start();

        openLast();
        commit(false);
        showInspector(null, null);
    }

    /** Für Screenshots: eine kleine Beispiel-Show ohne Song. */
    void demo() {
        Show s = new Show();
        s.name = "Beispiel";
        s.length = 24;
        List<Fixture> fx = core.project.fixtures;
        Show.Track all = new Show.Track();
        all.name = "Alles";
        s.tracks.add(all);
        for (int i = 0; i < Math.min(3, fx.size()); i++) {
            Show.Track t = new Show.Track();
            t.name = fx.get(i).name;
            t.targets = new ArrayList<>(List.of(Targets.fixture(fx.get(i).id)));
            s.tracks.add(t);
        }
        if (!fx.isEmpty()) {
            for (int k = 0; k < 3; k++) {
                Show.Track t = new Show.Track();
                t.name = fx.get(0).name + " · LED " + (k + 1);
                t.targets = new ArrayList<>(List.of(Targets.led(fx.get(0).id, k)));
                s.tracks.add(t);
            }
        }
        for (int i = 0; i < s.tracks.size(); i++) {
            Show.Track t = s.tracks.get(i);
            for (double start = i * 0.5; start < 22; start += 2 + i * 0.25) {
                Show.Clip c = new Show.Clip();
                c.start = start;
                c.end = start + 1 + (i % 3) * 0.5;
                c.look = i == 0 && start > 10 ? rainbowLook() : Project.Layer.solid(TimelineCanvas.trackColor(i));
                t.clips.add(c);
            }
        }
        s.markers.add(new Show.Marker(4, "Strophe"));
        s.markers.add(new Show.Marker(8, "Refrain"));
        setShow(s);
        if (!fx.isEmpty()) {
            // Sequenz: die LEDs des ersten Lüfters nacheinander, als eigene Spur oben
            Show.Track seqTrack = new Show.Track();
            seqTrack.name = "Sequenz " + fx.get(0).name;
            seqTrack.targets = new ArrayList<>(List.of(Targets.fixture(fx.get(0).id)));
            de.knirpslarsi.ledstudio.model.Sequence.Spec spec = new de.knirpslarsi.ledstudio.model.Sequence.Spec();
            spec.step = 0.25;
            spec.hold = 2;
            spec.order = "pingpong";
            seqTrack.clips.addAll(de.knirpslarsi.ledstudio.model.Sequence.build(
                    de.knirpslarsi.ledstudio.model.Sequence.leds(seqTrack.targets, fx, core.compiled(), false), 0.5, spec, new java.util.Random(1)));
            s.tracks.add(0, seqTrack);
            setShow(s);
            Show.Clip pick = seqTrack.clips.get(3);
            timeline.select(seqTrack, pick);
            player.seek(1.35);
        }
    }

    private static Project.Layer rainbowLook() {
        Project.Layer l = new Project.Layer();
        l.effect = "rainbow";
        return l;
    }

    // ---- Datei

    private void openLast() {
        String last = core.project.lastShow;
        if (last != null && !last.isBlank() && Files.exists(Path.of(last))) {
            try {
                load(Path.of(last));
                return;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Letzte Show nicht lesbar", e);
            }
        }
        show = starterShow();
        setShow(show);
    }

    /** Neue Show mit einer Spur für alles, damit man direkt loslegen kann. */
    private Show starterShow() {
        Show s = new Show();
        Show.Track t = new Show.Track();
        t.name = "Alles";
        s.tracks.add(t);
        return s;
    }

    private void newShow() {
        if (dirty && !Ui.confirm(getScene().getWindow(), "Neue Show", "Ungespeicherte Änderungen verwerfen?")) return;
        player.stop();
        player.setSong(null);
        file = null;
        setShow(starterShow());
        history.clear();
        historyIndex = -1;
        commit(false);
    }

    private FileChooser chooser(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        try {
            Files.createDirectories(Show.folder());
            fc.setInitialDirectory(file != null ? file.getParent().toFile() : Show.folder().toFile());
        } catch (IOException ignored) {
        }
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("LED-Studio-Show", "*" + Show.EXTENSION));
        return fc;
    }

    private void open() {
        if (dirty && !Ui.confirm(getScene().getWindow(), "Show öffnen", "Ungespeicherte Änderungen verwerfen?")) return;
        File f = chooser("Show öffnen").showOpenDialog(getScene().getWindow());
        if (f == null) return;
        try {
            load(f.toPath());
            history.clear();
            historyIndex = -1;
            commit(false);
        } catch (IOException e) {
            Ui.error(getScene().getWindow(), "Show öffnen", "Die Datei ließ sich nicht lesen: " + e.getMessage());
        }
    }

    private void load(Path path) throws IOException {
        Show s = Show.load(path);
        file = path;
        player.stop();
        setShow(s);
        dirty = false;
        rememberFile();
        if (!s.audio.isBlank()) loadSong(Path.of(s.audio), false);
        else player.setSong(null);
        updateTitle();
    }

    private void save() {
        if (file == null) {
            saveAs();
            return;
        }
        try {
            show.save(file);
            dirty = false;
            rememberFile();
            updateTitle();
        } catch (IOException e) {
            Ui.error(getScene().getWindow(), "Speichern", "Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    private void saveAs() {
        FileChooser fc = chooser("Show speichern");
        fc.setInitialFileName(show.name.replaceAll("[\\\\/:*?\"<>|]", "_") + Show.EXTENSION);
        File f = fc.showSaveDialog(getScene().getWindow());
        if (f == null) return;
        Path p = f.toPath();
        if (!p.getFileName().toString().endsWith(Show.EXTENSION)) p = p.resolveSibling(p.getFileName() + Show.EXTENSION);
        file = p;
        String base = p.getFileName().toString();
        if ("Neue Show".equals(show.name)) show.name = base.substring(0, base.length() - Show.EXTENSION.length());
        save();
    }

    private void rememberFile() {
        if (file == null) return;
        core.project.lastShow = file.toAbsolutePath().toString();
        core.project.save();
    }

    private void updateTitle() {
        title.setText("Show: " + show.name + (dirty ? " *" : "") + (file == null ? "" : "  ·  " + file.getFileName()));
    }

    // ---- Song

    private void chooseSong() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Song laden");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Musik (MP3, WAV)", "*.mp3", "*.wav", "*.aif", "*.aiff"));
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f != null) loadSong(f.toPath(), true);
    }

    private void loadSong(Path path, boolean userChoice) {
        songLabel.setText("Lade " + path.getFileName() + " …");
        Song[] result = new Song[1];
        core.background(() -> {
            result[0] = Song.load(path);
        }, () -> {
            Song song = result[0];
            player.setSong(song);
            timeline.song = song;
            if (userChoice) {
                show.audio = path.toAbsolutePath().toString();
                changed();
            }
            songLabel.setText(path.getFileName() + " · " + TimelineCanvas.format(song.duration, false) + " · Beats werden erkannt …");
            timeline.requestLayout();
            timeline.redraw();
            syncScrollbar();
            core.background(song::analyse, () -> {
                songLabel.setText(path.getFileName() + " · " + TimelineCanvas.format(song.duration, false)
                        + String.format(" · %.0f BPM · %d Beats", song.bpm, song.beats.size()));
                timeline.redraw();
            }, err -> songLabel.setText(path.getFileName() + " · Beat-Erkennung fehlgeschlagen"));
        }, err -> {
            songLabel.setText("Song nicht ladbar: " + err);
            if (!userChoice) player.setSong(null);
        });
    }

    // ---- Abspielen

    private void togglePlay() {
        if (player.playing()) {
            player.pause();
            finishHeld();
        } else {
            player.play();
        }
        playButton.setSelected(player.playing());
        playButton.setText(player.playing() ? "⏸  Pause" : "▶  Abspielen");
        refreshEngine();
    }

    private void seek(double t) {
        player.seek(Math.min(t, Math.max(player.duration(), timeline.duration())));
        timeline.redraw();
    }

    /** LEDs zeigen die Show, solange sie läuft oder die Seite mit „Auf den LEDs zeigen“ offen ist. */
    private void refreshEngine() {
        boolean active = player.playing() || (isVisible() && onLeds.isSelected());
        if (active || engineShowing) core.engine.setShow(show, active ? player::time : null);
        engineShowing = active;
    }

    private void tick() {
        double t = player.time();
        timeLabel.setText(TimelineCanvas.format(t, true));
        if (playButton.isSelected() != player.playing()) {
            playButton.setSelected(player.playing());
            playButton.setText(player.playing() ? "⏸  Pause" : "▶  Abspielen");
            refreshEngine();
        }
        // Beim Abspielen mitlaufen
        if (player.playing()) {
            double vis = timeline.visibleSeconds();
            if (t > timeline.scroll + vis * 0.85 || t < timeline.scroll) {
                timeline.scroll = Math.max(0, t - vis * 0.15);
                syncScrollbar();
            }
            for (Show.Clip c : held.values()) c.end = Math.max(c.start + 0.05, t);
        }
        timeline.redraw();
        if (picker != null) picker.redraw();
    }

    private void syncScrollbar() {
        double vis = timeline.visibleSeconds(), total = Math.max(timeline.duration() + 2, vis);
        hbar.setMin(0);
        hbar.setMax(Math.max(0, total - vis));
        hbar.setVisibleAmount(vis);
        hbar.setValue(Math.min(timeline.scroll, hbar.getMax()));
    }

    private void applyLength() {
        try {
            double v = Double.parseDouble(lengthField.getText().replace(',', '.'));
            if (v > 0 && v != show.length) {
                show.length = v;
                player.setLength(v);
                changed();
            }
        } catch (NumberFormatException e) {
            lengthField.setText(String.valueOf((int) show.length));
        }
    }

    // ---- Bearbeiten

    private void setShow(Show s) {
        show = s;
        timeline.show = s;
        timeline.selectedClip = null;
        timeline.selection.clear();
        timeline.selectedTrack = s.tracks.isEmpty() ? null : s.tracks.get(0);
        player.setLength(s.length);
        lengthField.setText(String.valueOf((int) s.length));
        timeline.requestLayout();
        timeline.redraw();
        syncScrollbar();
        updateTitle();
        showInspector(timeline.selectedTrack, null);
        refreshEngine();
    }

    /** Nach jeder Änderung: rückgängig merken, LEDs aktualisieren. */
    private void changed() {
        dirty = true;
        commit(true);
        updateTitle();
        timeline.requestLayout();
        timeline.redraw();
        syncScrollbar();
        refreshEngine();
    }

    private void commit(boolean markDirty) {
        String json = Project.GSON.toJson(show);
        if (historyIndex >= 0 && history.get(historyIndex).equals(json)) return;
        while (history.size() > historyIndex + 1) history.remove(history.size() - 1);
        history.add(json);
        historyIndex = history.size() - 1;
        while (history.size() > 100) {
            history.remove(0);
            historyIndex--;
        }
    }

    private void undo(int direction) {
        int i = historyIndex + direction;
        if (i < 0 || i >= history.size()) return;
        historyIndex = i;
        Show s = Project.GSON.fromJson(history.get(i), Show.class);
        String audio = show.audio;
        setShow(s);
        if (!s.audio.equals(audio) && !s.audio.isBlank()) loadSong(Path.of(s.audio), false);
        dirty = true;
        updateTitle();
    }

    private void addTrack() {
        Show.Track t = new Show.Track();
        t.name = "Spur " + (show.tracks.size() + 1);
        show.tracks.add(t);
        timeline.select(t, null);
        changed();
        showInspector(t, null);
    }

    private void selected(Show.Track track, Show.Clip clip) {
        if (timeline.selection.size() > 1) multiInspector();
        else showInspector(track, clip);
    }

    private javafx.scene.control.CheckMenuItem snapItem(String text, java.util.function.BooleanSupplier get,
                                                        java.util.function.Consumer<Boolean> set) {
        javafx.scene.control.CheckMenuItem item = new javafx.scene.control.CheckMenuItem(text);
        item.setSelected(get.getAsBoolean());
        item.selectedProperty().addListener((o, a, b) -> set.accept(b));
        return item;
    }

    // ---- Markierungen, Auswahl, Zwischenablage

    private void addMarker(double t) {
        show.markers.add(new Show.Marker(t, "M" + (show.markers.size() + 1)));
        show.markers.sort((a, b) -> Double.compare(a.time, b.time));
        changed();
    }

    private void deleteSelection() {
        if (timeline.selection.isEmpty()) return;
        ShowEdit.delete(show, timeline.selection);
        timeline.select(timeline.selectedTrack, null);
        changed();
        showInspector(timeline.selectedTrack, null);
    }

    private void copySelection() {
        if (!timeline.selection.isEmpty()) clipboard = ShowEdit.copy(show, timeline.selection);
    }

    private void paste(double at) {
        if (clipboard.isEmpty() || show.tracks.isEmpty()) return;
        int first = Math.max(0, show.tracks.indexOf(timeline.selectedTrack));
        List<Show.Clip> added = ShowEdit.paste(show, clipboard, at, first);
        if (rerollCopies.isSelected()) reroll(added);
        timeline.selection.clear();
        timeline.selection.addAll(added);
        timeline.selectedClip = added.isEmpty() ? null : added.get(added.size() - 1);
        changed();
        selected(timeline.selectedTrack, timeline.selectedClip);
    }

    private void duplicateSelection() {
        if (timeline.selection.isEmpty()) return;
        List<Show.Clip> added = ShowEdit.duplicate(show, timeline.selection);
        if (rerollCopies.isSelected()) reroll(added);
        timeline.selection.clear();
        timeline.selection.addAll(added);
        timeline.selectedClip = added.isEmpty() ? null : added.get(added.size() - 1);
        changed();
        selected(timeline.selectedTrack, timeline.selectedClip);
    }

    /** LEDs einer Spur als "bauteil#index", entlang der Kette. */
    private List<String> trackLeds(Show.Track track) {
        return track == null ? List.of() : de.knirpslarsi.ledstudio.model.Sequence.leds(track.targets, core.project.fixtures, core.compiled(), false);
    }

    private void reroll(java.util.Collection<Show.Clip> clips) {
        for (Show.Clip c : clips) ShowEdit.reroll(c, trackLeds(ShowEdit.trackOf(show, c)), dice);
    }

    private void rerollSelection() {
        if (timeline.selection.isEmpty()) return;
        reroll(timeline.selection);
        changed();
        selected(timeline.selectedTrack, timeline.selectedClip);
    }

    private void contextMenu(javafx.scene.input.MouseEvent e, Show.Track track, Show.Clip clip, Show.Marker marker, double t) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        if (marker != null) {
            menu.getItems().addAll(
                    item("Markierung umbenennen …", () -> {
                        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog(marker.name);
                        d.setTitle("Markierung");
                        d.setHeaderText("Name der Markierung");
                        d.initOwner(getScene().getWindow());
                        d.showAndWait().ifPresent(n -> {
                            marker.name = n.trim();
                            changed();
                        });
                    }),
                    item("Hierhin springen", () -> seek(marker.time)),
                    item("Markierung löschen", () -> {
                        show.markers.remove(marker);
                        changed();
                    }));
        } else if (clip != null) {
            int n = timeline.selection.size();
            String what = n > 1 ? n + " Clips " : "Clip ";
            menu.getItems().addAll(
                    item(what + "löschen", this::deleteSelection),
                    item(what + "duplizieren", this::duplicateSelection),
                    item(what + "neu würfeln", this::rerollSelection),
                    item(what + "kopieren", this::copySelection),
                    item(what + "ausschneiden", () -> {
                        copySelection();
                        deleteSelection();
                    }),
                    new javafx.scene.control.SeparatorMenuItem(),
                    item("Abspielkopf an den Anfang", () -> seek(clip.start)),
                    item("Markierung am Anfang setzen", () -> addMarker(clip.start)));
        } else {
            javafx.scene.control.MenuItem pasteHere = item("Einfügen hier", () -> paste(timeline.snap(t, false, null, null)));
            pasteHere.setDisable(clipboard.isEmpty());
            menu.getItems().addAll(
                    item("Clip hier anlegen", () -> {
                        if (track == null) return;
                        Show.Clip c = timeline.newClip(track, timeline.snap(t, false, null, null));
                        track.clips.add(c);
                        timeline.select(track, c);
                        changed();
                    }),
                    pasteHere,
                    item("Markierung hier setzen", () -> addMarker(timeline.snap(t, false, null, null))),
                    item("Abspielkopf hierhin", () -> seek(t)));
        }
        menu.show(timeline, e.getScreenX(), e.getScreenY());
    }

    private static javafx.scene.control.MenuItem item(String text, Runnable action) {
        javafx.scene.control.MenuItem m = new javafx.scene.control.MenuItem(text);
        m.setOnAction(e -> action.run());
        return m;
    }

    /** Mehrere Clips markiert: gemeinsame Aktionen. */
    private void multiInspector() {
        inspector.getChildren().clear();
        picker = null;
        int n = timeline.selection.size();
        ColorPicker color = new ColorPicker(TimelineCanvas.clipColor(timeline.selection.iterator().next()));
        color.setMaxWidth(Double.MAX_VALUE);
        color.getStyleClass().add("button");
        color.setOnAction(e -> {
            String hex = Colors.toHex(Ui.rgb(color.getValue()));
            for (Show.Clip c : timeline.selection) {
                c.look.effect = "static";
                c.look.params = new LinkedHashMap<>(Map.of("use_color", 1, "color", hex));
            }
            changed();
        });
        Show.Clip first = timeline.selection.iterator().next();
        inspector.getChildren().addAll(
                Ui.section(n + " Clips markiert"),
                muted("Ziehen verschiebt alle gemeinsam. Strg+Klick nimmt einzelne heraus oder hinzu."),
                Ui.field("Allen eine Farbe geben", color),
                Ui.slider("Einblenden für alle (s)", 0, 5, first.fadeIn, 0.01, v -> {
                    timeline.selection.forEach(c -> c.fadeIn = v);
                    changed();
                }),
                Ui.slider("Ausblenden für alle (s)", 0, 5, first.fadeOut, 0.01, v -> {
                    timeline.selection.forEach(c -> c.fadeOut = v);
                    changed();
                }),
                Ui.slider("Helligkeit für alle", 0, 2, first.look.brightness, 0.01, v -> {
                    timeline.selection.forEach(c -> c.look.brightness = v);
                    changed();
                }),
                Ui.row(Ui.button("Löschen", this::deleteSelection), Ui.button("Duplizieren", this::duplicateSelection),
                        Ui.button("Kopieren", this::copySelection)),
                Ui.section("Zufall"),
                Ui.row(Ui.button("Alle neu würfeln", "Neue zufällige LEDs und neuer Zufall für jeden markierten Clip", this::rerollSelection),
                        Ui.button("LEDs mischen", "Die LEDs der markierten Clips untereinander vertauschen", () -> {
                            ShowEdit.shuffleLeds(new ArrayList<>(timeline.selection), dice);
                            changed();
                        })),
                muted("„LEDs mischen“ vertauscht die LEDs der markierten Clips, z. B. eine Sequenz in neuer zufälliger Reihenfolge."));
    }

    private void keyPressed(KeyEvent e) {
        // Eingabefelder, Regler und Auswahllisten behalten ihre eigenen Tasten.
        if (e.getTarget() instanceof javafx.scene.control.TextInputControl || e.getTarget() instanceof Slider
                || e.getTarget() instanceof javafx.scene.control.ComboBoxBase<?>) return;
        Show.Clip c = timeline.selectedClip;
        Show.Track tr = timeline.selectedTrack;
        if (e.getCode() == KeyCode.SPACE) {
            togglePlay();
        } else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            deleteSelection();
        } else if (e.isControlDown() && e.getCode() == KeyCode.D) {
            duplicateSelection();
        } else if (e.isControlDown() && e.getCode() == KeyCode.C) {
            copySelection();
        } else if (e.isControlDown() && e.getCode() == KeyCode.X) {
            copySelection();
            deleteSelection();
        } else if (e.isControlDown() && e.getCode() == KeyCode.V) {
            paste(player.time());
        } else if (e.isControlDown() && e.getCode() == KeyCode.A) {
            timeline.selection.clear();
            show.tracks.forEach(t -> timeline.selection.addAll(t.clips));
            timeline.selectedClip = timeline.selection.stream().reduce((a, b) -> b).orElse(null);
            selected(tr, timeline.selectedClip);
            timeline.redraw();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            timeline.select(tr, null);
        } else if (e.getCode() == KeyCode.M && !e.isControlDown()) {
            addMarker(player.time());
        } else if ((e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) && e.isControlDown()) {
            // Markierte Clips um einen Beat schieben
            if (!timeline.selection.isEmpty()) {
                int dir = e.getCode() == KeyCode.RIGHT ? 1 : -1;
                double ref = timeline.selection.iterator().next().start;
                ShowEdit.move(timeline.selection, ShowEdit.step(ref, dir, timeline.beats()) - ref);
                changed();
                selected(tr, timeline.selectedClip);
            }
        } else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
            int dir = e.getCode() == KeyCode.RIGHT ? 1 : -1;
            double t = player.time();
            for (int i = 0; i < (e.isShiftDown() ? 4 : 1); i++) t = ShowEdit.step(t, dir, timeline.beats());
            seek(t);
            keepInView(t);
        } else if (e.getCode() == KeyCode.HOME) {
            seek(0);
            keepInView(0);
        } else if (e.getCode() == KeyCode.END) {
            seek(timeline.duration());
            keepInView(timeline.duration());
        } else if (e.getCode() == KeyCode.PAGE_DOWN || e.getCode() == KeyCode.PAGE_UP) {
            double t = player.time();
            Show.Marker target = null;
            for (Show.Marker m : show.markers) {
                if (e.getCode() == KeyCode.PAGE_DOWN && m.time > t + 1e-3 && (target == null || m.time < target.time)) target = m;
                if (e.getCode() == KeyCode.PAGE_UP && m.time < t - 1e-3 && (target == null || m.time > target.time)) target = m;
            }
            if (target != null) {
                seek(target.time);
                keepInView(target.time);
            }
        } else if (e.isControlDown() && e.getCode() == KeyCode.Z) {
            undo(-1);
        } else if (e.isControlDown() && e.getCode() == KeyCode.Y) {
            undo(1);
        } else if (e.isControlDown() && e.getCode() == KeyCode.S) {
            save();
        } else if (record.isSelected() && player.playing() && e.getCode().isDigitKey() && !e.isControlDown()) {
            int n = e.getCode().getCode() - KeyCode.DIGIT1.getCode();
            if (e.getCode().getName().startsWith("Numpad")) n = e.getCode().getCode() - KeyCode.NUMPAD1.getCode();
            if (n >= 0 && n < Math.min(9, show.tracks.size()) && !held.containsKey(n)) {
                Show.Track track = show.tracks.get(n);
                Show.Clip clip = timeline.newClip(track, player.time());
                clip.end = clip.start + 0.05;
                track.clips.add(clip);
                held.put(n, clip);
                timeline.recording = clip;
            }
        } else {
            return;
        }
        e.consume();
    }

    private void keepInView(double t) {
        double vis = timeline.visibleSeconds();
        if (t < timeline.scroll || t > timeline.scroll + vis * 0.9) {
            timeline.scroll = Math.max(0, t - vis * 0.2);
            syncScrollbar();
        }
        timeline.redraw();
    }

    private void keyReleased(KeyEvent e) {
        if (!e.getCode().isDigitKey()) return;
        int n = e.getCode().getCode() - KeyCode.DIGIT1.getCode();
        if (e.getCode().getName().startsWith("Numpad")) n = e.getCode().getCode() - KeyCode.NUMPAD1.getCode();
        Show.Clip c = held.remove(n);
        if (c != null) {
            c.end = Math.max(c.start + 0.05, player.time());
            timeline.recording = null;
            changed();
        }
    }

    private void finishHeld() {
        if (held.isEmpty()) return;
        held.clear();
        timeline.recording = null;
        changed();
    }

    // ---- Seitenleiste

    private void showInspector(Show.Track track, Show.Clip clip) {
        inspector.getChildren().clear();
        picker = null;
        if (clip != null && track != null) {
            clipInspector(track, clip);
        } else if (track != null) {
            trackInspector(track);
        } else {
            inspector.getChildren().addAll(Ui.section("Show"), muted("Wähle links eine Spur oder lege mit „+ Spur“ eine an."));
        }
    }

    private static Label muted(String text) {
        Label l = Ui.label(text, "muted");
        l.setWrapText(true);
        return l;
    }

    private void trackInspector(Show.Track track) {
        TextField name = new TextField(track.name);
        name.setOnAction(e -> {
            track.name = name.getText().isBlank() ? "Spur" : name.getText().trim();
            changed();
        });
        name.focusedProperty().addListener((o, a, f) -> {
            if (!f && !name.getText().equals(track.name)) {
                track.name = name.getText().isBlank() ? "Spur" : name.getText().trim();
                changed();
            }
        });
        CheckBox mute = new CheckBox("Stumm");
        mute.setSelected(track.mute);
        mute.setOnAction(e -> {
            track.mute = mute.isSelected();
            changed();
        });
        picker = new LedPicker(core::compiled, () -> core.project.fixtures, core.engine::preview);
        picker.edit(track.targets, t -> {
            track.targets = new ArrayList<>(t);
            changed();
        });
        Label targetText = muted(describe(track.targets));
        Button all = Ui.button("Alles", () -> {
            track.targets = new ArrayList<>();
            changed();
            showInspector(track, null);
        });
        Button none = Ui.button("Nichts", () -> {
            track.targets = new ArrayList<>(List.of(Targets.NONE));
            changed();
            showInspector(track, null);
        });
        Button perLed = Ui.button("Eine Spur je LED", "Legt für jede ausgewählte LED eine eigene Spur an", () -> splitPerLed(track));
        Button perFixture = Ui.button("Eine Spur je Lüfter", "Legt für jedes ausgewählte Bauteil eine eigene Spur an", () -> splitPerFixture(track));
        Button up = Ui.button("▲", "Spur nach oben", () -> moveTrack(track, -1));
        Button down = Ui.button("▼", "Spur nach unten", () -> moveTrack(track, 1));
        Button delete = Ui.button("Spur löschen", () -> {
            if (!track.clips.isEmpty() && !Ui.confirm(getScene().getWindow(), "Spur löschen", "Die Spur hat " + track.clips.size() + " Clips. Trotzdem löschen?")) return;
            show.tracks.remove(track);
            timeline.selection.removeAll(track.clips);
            timeline.selectedTrack = null;
            changed();
            showInspector(null, null);
        });
        inspector.getChildren().addAll(
                Ui.section("Spur"),
                Ui.field("Name", name), mute,
                Ui.section("Ziel"),
                muted("Klick auf eine LED wählt sie einzeln, Umschalt+Klick den ganzen Lüfter. Weiß umrandet = gehört zur Spur."),
                picker, targetText, Ui.row(all, none),
                Ui.row(perLed, perFixture));
        sequenceSection(track);
        inspector.getChildren().addAll(
                Ui.section("Reihenfolge"),
                muted("Obere Spuren liegen über unteren. Die Tasten 1–9 nehmen beim Abspielen auf den ersten neun Spuren auf."),
                Ui.row(up, down, Ui.grow(), delete));
    }

    /** Sequenz-Assistent: legt aus den LEDs der Spur nacheinander leuchtende Clips an. */
    private void sequenceSection(Show.Track track) {
        de.knirpslarsi.ledstudio.model.Sequence.Spec spec = seq;
        Map<String, String> stepChoices = new LinkedHashMap<>();
        stepChoices.put("0.25", "¼ Beat");
        stepChoices.put("0.5", "½ Beat");
        stepChoices.put("1", "1 Beat");
        stepChoices.put("2", "2 Beats");
        stepChoices.put("4", "1 Takt");
        stepChoices.put("sec", "Eigene Sekunden");
        VBox seconds = new VBox(Ui.slider("Schritt (Sekunden)", 0.02, 4, spec.step, 0.01, v -> spec.step = v));
        Runnable showSeconds = () -> {
            seconds.setVisible("sec".equals(seqStep));
            seconds.setManaged("sec".equals(seqStep));
        };
        showSeconds.run();
        ColorPicker from = new ColorPicker(Ui.fx(Colors.parseHex(spec.colorA)));
        ColorPicker to = new ColorPicker(Ui.fx(Colors.parseHex(spec.colorB)));
        for (ColorPicker p : List.of(from, to)) {
            p.getStyleClass().add("button");
            p.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(p, Priority.ALWAYS);
        }
        from.setOnAction(e -> spec.colorA = Colors.toHex(Ui.rgb(from.getValue())));
        to.setOnAction(e -> spec.colorB = Colors.toHex(Ui.rgb(to.getValue())));
        Label result = muted("");
        Button create = Ui.primary("Sequenz am Abspielkopf anlegen", () -> {
            List<String> leds = de.knirpslarsi.ledstudio.model.Sequence.leds(track.targets, core.project.fixtures, core.compiled(),
                    "clicked".equals(spec.order));
            if (leds.isEmpty()) {
                result.setText("Die Spur hat keine LEDs. Wähle oben im Ziel LEDs aus.");
                return;
            }
            double start = timeline.snap(player.time(), false, null, null);
            if (!"sec".equals(seqStep)) spec.step = Double.parseDouble(seqStep) * timeline.beatLength(start);
            List<Show.Clip> clips = de.knirpslarsi.ledstudio.model.Sequence.build(leds, start, spec, new java.util.Random());
            track.clips.addAll(clips);
            timeline.selection.clear();
            timeline.selection.addAll(clips);
            timeline.selectedClip = clips.isEmpty() ? null : clips.get(clips.size() - 1);
            changed();
            result.setText(clips.size() + " Clips angelegt und markiert. Mit Entf wieder entfernen, mit der Maus gemeinsam verschieben.");
        });
        create.setMaxWidth(Double.MAX_VALUE);
        inspector.getChildren().addAll(
                Ui.section("Sequenz"),
                muted("Die LEDs der Spur leuchten nacheinander auf, jede als eigener Clip. Für eine eigene Reihenfolge die LEDs "
                        + "oben einzeln in der gewünschten Folge anklicken und „In Klick-Reihenfolge“ wählen."),
                Ui.field("Reihenfolge", choice(de.knirpslarsi.ledstudio.model.Sequence.ORDERS, spec.order, v -> spec.order = v)),
                Ui.field("Schrittlänge", choice(stepChoices, seqStep, v -> {
                    seqStep = v;
                    showSeconds.run();
                })),
                seconds,
                Ui.slider("LEDs pro Schritt", 1, 16, spec.group, 1, v -> spec.group = (int) Math.round(v)),
                Ui.slider("Nachleuchten (Schritte)", 1, 8, spec.hold, 0.1, v -> spec.hold = v),
                Ui.slider("Wiederholungen", 1, 16, spec.repeats, 1, v -> spec.repeats = (int) Math.round(v)),
                Ui.field("Farbe von … bis", new HBox(6, from, to)),
                create, result);
    }

    private String describe(List<String> targets) {
        if (targets.isEmpty()) return "Ziel: alles";
        if (targets.contains(Targets.NONE)) return "Ziel: nichts ausgewählt";
        long leds = targets.stream().filter(t -> t.startsWith("led:")).count();
        long parts = targets.size() - leds;
        return "Ziel: " + (parts > 0 ? parts + " Bauteil(e)/Gerät(e)" : "") + (parts > 0 && leds > 0 ? " und " : "") + (leds > 0 ? leds + " einzelne LED(s)" : "");
    }

    private void moveTrack(Show.Track track, int dir) {
        int i = show.tracks.indexOf(track), j = i + dir;
        if (i < 0 || j < 0 || j >= show.tracks.size()) return;
        show.tracks.remove(i);
        show.tracks.add(j, track);
        changed();
    }

    /** Jede LED der Spur bekommt eine eigene Spur (Name „Lüfter · LED n“). */
    private void splitPerLed(Show.Track track) {
        CompiledLayout lay = core.compiled();
        List<Fixture> fx = core.project.fixtures;
        List<Show.Track> created = new ArrayList<>();
        for (CompiledLayout.Led l : lay.leds) {
            if (l.fixture >= fx.size() || !LedPicker.contains(track.targets, fx, l)) continue;
            Show.Track t = new Show.Track();
            t.name = fx.get(l.fixture).name + " · LED " + (l.k + 1);
            t.targets = new ArrayList<>(List.of(Targets.led(fx.get(l.fixture).id, l.k)));
            if (created.stream().noneMatch(c -> c.targets.equals(t.targets))) created.add(t);
        }
        if (created.size() > 200 && !Ui.confirm(getScene().getWindow(), "Spuren anlegen", created.size() + " Spuren anlegen?")) return;
        show.tracks.addAll(show.tracks.indexOf(track) + 1, created);
        changed();
    }

    private void splitPerFixture(Show.Track track) {
        List<Fixture> fx = core.project.fixtures;
        List<Show.Track> created = new ArrayList<>();
        for (String id : Targets.resolve(track.targets, fx)) {
            Fixture f = fx.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
            if (f == null) continue;
            Show.Track t = new Show.Track();
            t.name = f.name;
            t.targets = new ArrayList<>(List.of(Targets.fixture(id)));
            created.add(t);
        }
        show.tracks.addAll(show.tracks.indexOf(track) + 1, created);
        changed();
    }

    private void clipInspector(Show.Track track, Show.Clip clip) {
        double max = Math.max(timeline.duration(), clip.end + 10);
        Map<String, String> effects = new LinkedHashMap<>();
        for (Effect e : Effects.catalog()) effects.put(e.id(), "static".equals(e.id()) ? "Farbe (fest)" : e.title());
        ComboBox<String> effect = new ComboBox<>(FXCollections.observableArrayList(effects.values()));
        effect.setMaxWidth(Double.MAX_VALUE);
        effect.setValue(effects.getOrDefault(clip.look.effect, clip.look.effect));
        List<String> ids = new ArrayList<>(effects.keySet());
        effect.setOnAction(e -> {
            int i = effect.getSelectionModel().getSelectedIndex();
            if (i < 0 || ids.get(i).equals(clip.look.effect)) return;
            String keepColor = clip.look.params.get("color") instanceof String s ? s : null;
            clip.look.effect = ids.get(i);
            clip.look.params = new LinkedHashMap<>();
            if ("static".equals(clip.look.effect)) {
                clip.look.params.put("use_color", 1);
                clip.look.params.put("color", keepColor != null ? keepColor : TimelineCanvas.trackColor(show.tracks.indexOf(track)));
            }
            changed();
            showInspector(track, clip);
        });
        Button dup = Ui.button("Duplizieren", this::duplicateSelection);
        Button del = Ui.button("Löschen", this::deleteSelection);
        Button toTrack = Ui.button("Zur Spur", () -> timeline.select(track, null));
        inspector.getChildren().addAll(
                Ui.section("Clip auf „" + track.name + "“"),
                Ui.slider("Start (s)", 0, max, clip.start, 0.01, v -> {
                    double len = clip.length();
                    clip.start = Math.max(0, v);
                    clip.end = clip.start + len;
                    changed();
                }),
                Ui.slider("Länge (s)", 0.05, 60, clip.length(), 0.01, v -> {
                    clip.end = clip.start + Math.max(0.05, v);
                    changed();
                }),
                Ui.slider("Einblenden (s)", 0, 5, clip.fadeIn, 0.01, v -> {
                    clip.fadeIn = v;
                    changed();
                }),
                Ui.slider("Ausblenden (s)", 0, 5, clip.fadeOut, 0.01, v -> {
                    clip.fadeOut = v;
                    changed();
                }),
                Ui.section("Effekt"), effect);
        clipLeds(track, clip);
        inspector.getChildren().add(Ui.section("Einstellungen des Effekts"));
        Effect e = Effects.create(clip.look.effect);
        if (e != null) {
            Params values = new Params(e.params(), clip.look.params);
            for (ParamSpec spec : e.params()) {
                if (!spec.visible(values)) continue;
                if ("static".equals(clip.look.effect) && spec.name.equals("use_color")) continue;
                inspector.getChildren().add(control(spec, values, clip, track));
            }
        }
        inspector.getChildren().addAll(
                Ui.section("Aussehen"),
                Ui.slider("Helligkeit", 0, 2, clip.look.brightness, 0.01, v -> {
                    clip.look.brightness = v;
                    changed();
                }),
                Ui.slider("Tempo des Effekts", 0, 5, clip.look.speed, 0.01, v -> {
                    clip.look.speed = v;
                    changed();
                }),
                SpeedControls.build(clip.look, true, this::changed),
                Ui.field("Mischen mit Spuren darunter", choice(Map.of("normal", "Normal (verdeckt)", "add", "Addieren", "max", "Aufhellen"),
                        clip.look.blend, v -> {
                            clip.look.blend = v;
                            changed();
                        })),
                Ui.row(dup, del, Ui.grow(), toTrack));
    }

    /** Eigene LEDs eines Clips; ohne Auswahl gilt das Ziel der Spur. */
    private void clipLeds(Show.Track track, Show.Clip clip) {
        picker = new LedPicker(core::compiled, () -> core.project.fixtures, core.engine::preview);
        Label state = muted(clip.targets.isEmpty() ? "Nutzt das Ziel der Spur. Klick auf LEDs gibt dem Clip eigene LEDs."
                : describe(clip.targets).replace("Ziel:", "Eigene LEDs:"));
        picker.edit(clip.targetsIn(track), t -> {
            // Alles gewählt heißt hier: alle Bauteile, nicht „wie die Spur“.
            List<String> chosen = new ArrayList<>(t);
            if (chosen.isEmpty()) core.project.fixtures.forEach(f -> chosen.add(Targets.fixture(f.id)));
            clip.targets = chosen;
            clip.randomLeds = 0; // selbst gewählt: nicht mehr zufällig
            state.setText(describe(chosen).replace("Ziel:", "Eigene LEDs:"));
            changed();
        });
        Button reset = Ui.button("Wie die Spur", () -> {
            clip.targets = new ArrayList<>();
            clip.randomLeds = 0;
            changed();
            showInspector(track, clip);
        });
        reset.setDisable(clip.targets.isEmpty());
        List<String> pool = trackLeds(track);
        VBox random = new VBox(8);
        Button dice = Ui.button("🎲  Neu würfeln", "Neue zufällige LEDs und neuer Zufall für den Effekt", () -> {
            ShowEdit.reroll(clip, pool, this.dice);
            changed();
            showInspector(track, clip);
        });
        dice.setDisable(clip.randomLeds <= 0 && !ShowEdit.hasSeed(clip));
        random.getChildren().addAll(
                Ui.slider("Zufällige LEDs der Spur (Anzahl, 0 = aus)", 0, Math.max(1, pool.size()), clip.randomLeds, 1, v -> {
                    int n = (int) Math.round(v);
                    if (n == clip.randomLeds) return;
                    clip.randomLeds = n;
                    if (n > 0) ShowEdit.reroll(clip, pool, this.dice);
                    else clip.targets = new ArrayList<>();
                    dice.setDisable(n <= 0 && !ShowEdit.hasSeed(clip));
                    state.setText(clip.targets.isEmpty() ? "Nutzt das Ziel der Spur." : describe(clip.targets).replace("Ziel:", "Eigene LEDs:"));
                    picker.edit(clip.targetsIn(track), t -> {
                        List<String> chosen = new ArrayList<>(t);
                        if (chosen.isEmpty()) core.project.fixtures.forEach(f -> chosen.add(Targets.fixture(f.id)));
                        clip.targets = chosen;
                        clip.randomLeds = 0;
                        changed();
                    });
                    changed();
                }),
                dice);
        inspector.getChildren().addAll(Ui.section("LEDs dieses Clips"), picker, state, reset, random);
    }

    private ComboBox<String> choice(Map<String, String> values, String current, java.util.function.Consumer<String> set) {
        Map<String, String> ordered = new LinkedHashMap<>(values);
        List<String> keys = new ArrayList<>(ordered.keySet());
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(ordered.values()));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setValue(ordered.getOrDefault(current, ordered.values().iterator().next()));
        box.setOnAction(e -> {
            int i = box.getSelectionModel().getSelectedIndex();
            if (i >= 0) set.accept(keys.get(i));
        });
        return box;
    }

    /** Einstellungen eines Effekts im Clip, wie im Effekt-Reiter. */
    private Node control(ParamSpec s, Params values, Show.Clip clip, Show.Track track) {
        java.util.function.Consumer<Object> set = v -> {
            clip.look.params.put(s.name, v);
            changed();
        };
        Node n = switch (s.type) {
            case FLOAT, INT -> Ui.slider(s.label, s.min, s.max, values.d(s.name), s.step, set::accept);
            case HUE -> Ui.slider(s.label, 0, 1, values.d(s.name), 0.01, set::accept);
            case BOOL -> {
                CheckBox c = new CheckBox(s.label);
                c.setSelected(values.b(s.name));
                c.selectedProperty().addListener((o, a, b) -> set.accept(b ? 1 : 0));
                yield c;
            }
            case COLOR -> {
                ColorPicker picker = new ColorPicker(Ui.fx(values.color(s.name)));
                picker.setMaxWidth(Double.MAX_VALUE);
                picker.getStyleClass().add("button");
                picker.setOnAction(e -> set.accept(Colors.toHex(Ui.rgb(picker.getValue()))));
                yield Ui.field(s.label, picker);
            }
            case CHOICE -> {
                ComboBox<String> box = choice(s.choices, values.s(s.name), v -> {
                    set.accept(v);
                    showInspector(track, clip);
                });
                yield Ui.field(s.label, box);
            }
        };
        if (s.help == null) return n;
        return new VBox(2, n, muted(s.help));
    }
}
