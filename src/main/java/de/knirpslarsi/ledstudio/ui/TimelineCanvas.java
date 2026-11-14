package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.audio.MusicAnalyzer;
import de.knirpslarsi.ledstudio.audio.Song;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.model.Show;
import de.knirpslarsi.ledstudio.model.ShowEdit;
import de.knirpslarsi.ledstudio.util.Colors;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Zeitleiste der Show: oben Zeit mit Markierungen und Abspielkopf, Song-Teile und Wellenform, darunter je Spur eine
 * Zeile mit Clips. Clips lassen sich einzeln oder gemeinsam markieren, verschieben, in der Länge ziehen und rasten an
 * Beats, Markierungen, anderen Clips und am Abspielkopf ein.
 */
final class TimelineCanvas extends Region {
    static final double HEADER = 210, RULER = 30, WAVE = 58, TRACK = 36;
    private static final double EDGE = 6, GRAB = 6, SNAP_PX = 10;

    /** Was nach einem Rechtsklick angeboten wird. */
    interface ContextHandler {
        void show(MouseEvent e, Show.Track track, Show.Clip clip, Show.Marker marker, double time);
    }

    private final Canvas canvas = new Canvas();
    Show show = new Show();
    Song song;
    double pxPerSec = 80, scroll;
    final ShowEdit.Snap snap = new ShowEdit.Snap();
    Show.Track selectedTrack;
    /** Markierte Clips; der zuletzt angeklickte steht in der Seitenleiste. */
    final Set<Show.Clip> selection = new LinkedHashSet<>();
    Show.Clip selectedClip;
    /** Clip, der gerade per Taste aufgenommen wird (wird hervorgehoben). */
    Show.Clip recording;

    private final DoubleSupplier time;
    private final DoubleConsumer onSeek;
    private final BiConsumer<Show.Track, Show.Clip> onSelect;
    private final Runnable onChanged;
    ContextHandler onContext;
    /** Wird nach Zoom oder Scrollen aufgerufen (für die Bildlaufleiste). */
    Runnable onView;

    private enum Drag {NONE, SEEK, MOVE, LEFT, RIGHT, BAND, MARKER}

    private Drag drag = Drag.NONE;
    private double anchor, bandX, bandY, bandX2, bandY2, snapLine = Double.NaN;
    private final Map<Show.Clip, double[]> original = new HashMap<>();
    private final Set<Show.Clip> bandBase = new LinkedHashSet<>();
    private Show.Marker draggedMarker;
    private boolean moved;
    private double hoverX = -1;

    TimelineCanvas(DoubleSupplier time, DoubleConsumer onSeek, BiConsumer<Show.Track, Show.Clip> onSelect, Runnable onChanged) {
        this.time = time;
        this.onSeek = onSeek;
        this.onSelect = onSelect;
        this.onChanged = onChanged;
        getChildren().add(canvas);
        canvas.setOnMousePressed(this::pressed);
        canvas.setOnMouseDragged(this::dragged);
        canvas.setOnMouseReleased(this::released);
        canvas.setOnMouseMoved(this::hover);
        canvas.setOnMouseExited(e -> {
            hoverX = -1;
            redraw();
        });
        setOnScroll(this::scrolled);
        setMinHeight(RULER + WAVE + TRACK);
    }

    double visibleSeconds() {
        return Math.max(0.1, (getWidth() - HEADER) / pxPerSec);
    }

    double duration() {
        return Math.max(show.end(), song != null ? song.duration : 0);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    @Override
    protected double computePrefHeight(double width) {
        return RULER + WAVE + Math.max(1, show.tracks.size()) * TRACK + 30;
    }

    double x(double t) {
        return HEADER + (t - scroll) * pxPerSec;
    }

    double t(double x) {
        return scroll + (x - HEADER) / pxPerSec;
    }

    private int trackAt(double y) {
        double top = RULER + WAVE;
        if (y < top) return -1;
        int i = (int) ((y - top) / TRACK);
        return i < show.tracks.size() ? i : -1;
    }

    List<Double> beats() {
        return song == null ? List.of() : song.beats;
    }

    /** Zeit einrasten (Alt schaltet das Einrasten aus). */
    double snap(double t, boolean alt, Set<Show.Clip> exclude, Show.Marker marker) {
        if (alt) return Math.max(0, t);
        return ShowEdit.snap(t, SNAP_PX / pxPerSec, snap, show, beats(), pxPerSec > 160, time.getAsDouble(), exclude, marker);
    }

    double beatLength(double t) {
        List<Double> b = beats();
        if (b.size() < 2) return 0.5;
        for (int i = 0; i + 1 < b.size(); i++) if (b.get(i + 1) > t) return b.get(i + 1) - b.get(i);
        return b.get(b.size() - 1) - b.get(b.size() - 2);
    }

    void select(Show.Track track, Show.Clip clip) {
        selection.clear();
        if (clip != null) selection.add(clip);
        selectedClip = clip;
        if (track != null) selectedTrack = track;
        onSelect.accept(selectedTrack, clip);
        redraw();
    }

    // ---- Maus

    private Show.Clip clipAt(Show.Track track, double x) {
        for (int i = track.clips.size() - 1; i >= 0; i--) {
            Show.Clip c = track.clips.get(i);
            if (x >= x(c.start) - 2 && x <= x(c.end) + 2) return c;
        }
        return null;
    }

    private Show.Marker markerAt(double x) {
        for (Show.Marker m : show.markers) if (Math.abs(x(m.time) - x) <= GRAB) return m;
        return null;
    }

    private boolean nearPlayhead(double x) {
        return x > HEADER && Math.abs(x - x(time.getAsDouble())) <= GRAB;
    }

    private void hover(MouseEvent e) {
        hoverX = e.getX();
        double x = e.getX(), y = e.getY();
        int ti = trackAt(y);
        Cursor cursor = Cursor.DEFAULT;
        if (x > HEADER) {
            if (nearPlayhead(x)) cursor = Cursor.H_RESIZE;
            else if (y < RULER && markerAt(x) != null) cursor = Cursor.HAND;
            else if (y < RULER + WAVE) cursor = Cursor.TEXT;
            else if (ti >= 0) {
                Show.Clip c = clipAt(show.tracks.get(ti), x);
                if (c != null) {
                    double l = x(c.start), r = x(c.end);
                    boolean wide = r - l > 3 * EDGE;
                    cursor = wide && (x - l < EDGE || r - x < EDGE) ? Cursor.H_RESIZE : Cursor.OPEN_HAND;
                } else {
                    cursor = Cursor.CROSSHAIR;
                }
            }
        }
        canvas.setCursor(cursor);
        redraw();
    }

    private void pressed(MouseEvent e) {
        requestFocus();
        moved = false;
        double x = e.getX(), y = e.getY();
        int ti = trackAt(y);
        Show.Track track = ti >= 0 ? show.tracks.get(ti) : null;
        Show.Clip clip = track != null && x > HEADER ? clipAt(track, x) : null;

        if (e.getButton() == MouseButton.SECONDARY) {
            Show.Marker marker = y < RULER ? markerAt(x) : null;
            if (clip != null && !selection.contains(clip)) select(track, clip);
            else if (track != null) selectedTrack = track;
            if (onContext != null) onContext.show(e, track, clip, marker, Math.max(0, t(x)));
            return;
        }
        if (x < HEADER) {
            if (track != null) {
                // Klick auf das Lautsprecher-Feld schaltet stumm.
                if (x > HEADER - 28) {
                    track.mute = !track.mute;
                    onChanged.run();
                }
                selectedTrack = track;
                select(track, null);
            }
            return;
        }
        if (nearPlayhead(x)) {
            drag = Drag.SEEK;
            return;
        }
        if (y < RULER) {
            Show.Marker m = markerAt(x);
            if (m != null) {
                drag = Drag.MARKER;
                draggedMarker = m;
                if (e.getClickCount() == 2) onSeek.accept(m.time);
                return;
            }
        }
        if (y < RULER + WAVE || track == null) {
            drag = Drag.SEEK;
            onSeek.accept(Math.max(0, t(x)));
            return;
        }
        selectedTrack = track;
        if (clip == null) {
            if (e.getClickCount() == 2) {
                Show.Clip c = newClip(track, snap(t(x), e.isAltDown(), null, null));
                track.clips.add(c);
                select(track, c);
                onChanged.run();
                return;
            }
            // Rahmen aufziehen; ein einfacher Klick setzt den Abspielkopf (beim Loslassen).
            drag = Drag.BAND;
            bandX = bandX2 = x;
            bandY = bandY2 = y;
            bandBase.clear();
            if (e.isControlDown() || e.isShiftDown()) bandBase.addAll(selection);
            else {
                selection.clear();
                selectedClip = null;
                onSelect.accept(track, null);
            }
            redraw();
            return;
        }
        if (e.isControlDown() || e.isShiftDown()) {
            if (!selection.remove(clip)) selection.add(clip);
            selectedClip = selection.contains(clip) ? clip : selection.stream().reduce((a, b) -> b).orElse(null);
            onSelect.accept(track, selectedClip);
            if (!selection.contains(clip)) {
                redraw();
                return;
            }
        } else if (!selection.contains(clip)) {
            selection.clear();
            selection.add(clip);
        }
        selectedClip = clip;
        onSelect.accept(track, clip);
        double l = x(clip.start), r = x(clip.end);
        boolean wide = r - l > 3 * EDGE;
        if (wide && x - l < EDGE) drag = Drag.LEFT;
        else if (wide && r - x < EDGE) drag = Drag.RIGHT;
        else drag = Drag.MOVE;
        if (drag == Drag.MOVE) canvas.setCursor(Cursor.CLOSED_HAND);
        anchor = t(x);
        original.clear();
        for (Show.Clip c : selection) original.put(c, new double[]{c.start, c.end});
        redraw();
    }

    /** Neuer Clip: Aussehen wie der letzte Clip der Spur, Länge ein Beat. */
    Show.Clip newClip(Show.Track track, double start) {
        Show.Clip clip = track.clips.isEmpty() ? new Show.Clip() : track.clips.get(track.clips.size() - 1).copy();
        if (track.clips.isEmpty()) clip.look = Project.Layer.solid(trackColor(show.tracks.indexOf(track)));
        double len = Math.max(0.1, beatLength(start));
        clip.start = start;
        clip.end = start + len;
        return clip;
    }

    static String trackColor(int i) {
        String[] colors = {"#A020FF", "#00DDFF", "#FF2080", "#FFB000", "#40FF60", "#3060FF", "#FF4020", "#FFFFFF"};
        return colors[Math.floorMod(i, colors.length)];
    }

    private void dragged(MouseEvent e) {
        double x = e.getX(), t = t(x);
        hoverX = x;
        boolean alt = e.isAltDown();
        switch (drag) {
            case SEEK -> {
                double s = snap(t, alt, null, null);
                snapLine = s != t ? s : Double.NaN;
                onSeek.accept(Math.max(0, s));
            }
            case MARKER -> {
                double s = snap(t, alt, null, draggedMarker);
                snapLine = s != t ? s : Double.NaN;
                draggedMarker.time = s;
                moved = true;
            }
            case BAND -> {
                bandX2 = x;
                bandY2 = e.getY();
                updateBand();
            }
            case MOVE -> moveSelection(e, t, alt);
            case LEFT, RIGHT -> {
                Show.Clip c = selectedClip;
                if (c == null) break;
                moved = true;
                double s = snap(t, alt, Set.of(c), null);
                snapLine = s != t ? s : Double.NaN;
                if (drag == Drag.LEFT) c.start = Math.min(c.end - 0.05, s);
                else c.end = Math.max(c.start + 0.05, s);
            }
            default -> {
            }
        }
        redraw();
    }

    /** Verschiebt alle markierten Clips gemeinsam; der angefasste Clip rastet mit Anfang oder Ende ein. */
    private void moveSelection(MouseEvent e, double t, boolean alt) {
        Show.Clip c = selectedClip;
        if (c == null || !original.containsKey(c)) return;
        moved = true;
        double[] o = original.get(c);
        double raw = t - anchor;
        double startSnap = snap(o[0] + raw, alt, selection, null) - o[0];
        double endSnap = snap(o[1] + raw, alt, selection, null) - o[1];
        double delta = Math.abs(endSnap - raw) < Math.abs(startSnap - raw) ? endSnap : startSnap;
        snapLine = delta != raw ? (delta == endSnap ? o[1] + delta : o[0] + delta) : Double.NaN;
        double min = Double.MAX_VALUE;
        for (double[] v : original.values()) min = Math.min(min, v[0]);
        if (min + delta < 0) delta = -min;
        for (Map.Entry<Show.Clip, double[]> en : original.entrySet()) {
            en.getKey().start = en.getValue()[0] + delta;
            en.getKey().end = en.getValue()[1] + delta;
        }
        // Ein einzelner Clip darf in eine andere Spur wandern.
        int ti = trackAt(e.getY());
        if (selection.size() == 1 && ti >= 0) {
            Show.Track from = ShowEdit.trackOf(show, c), to = show.tracks.get(ti);
            if (from != null && from != to) {
                from.clips.remove(c);
                to.clips.add(c);
                selectedTrack = to;
                onSelect.accept(to, c);
            }
        }
    }

    private void updateBand() {
        double x1 = Math.min(bandX, bandX2), x2 = Math.max(bandX, bandX2), y1 = Math.min(bandY, bandY2), y2 = Math.max(bandY, bandY2);
        selection.clear();
        selection.addAll(bandBase);
        for (int i = 0; i < show.tracks.size(); i++) {
            double top = RULER + WAVE + i * TRACK, bottom = top + TRACK;
            if (bottom < y1 || top > y2) continue;
            for (Show.Clip c : show.tracks.get(i).clips) if (x(c.end) >= x1 && x(c.start) <= x2) selection.add(c);
        }
        selectedClip = selection.stream().reduce((a, b) -> b).orElse(null);
    }

    private void released(MouseEvent e) {
        Drag was = drag;
        drag = Drag.NONE;
        snapLine = Double.NaN;
        if (was == Drag.BAND) {
            boolean click = Math.abs(bandX2 - bandX) < 4 && Math.abs(bandY2 - bandY) < 4;
            if (click) onSeek.accept(snap(Math.max(0, t(e.getX())), e.isAltDown(), null, null));
            onSelect.accept(selectedTrack, selectedClip);
        } else if ((was == Drag.MOVE || was == Drag.LEFT || was == Drag.RIGHT || was == Drag.MARKER) && moved) {
            if (was == Drag.MARKER) show.markers.sort((a, b) -> Double.compare(a.time, b.time));
            onSelect.accept(selectedTrack, selectedClip);
            onChanged.run();
        }
        hover(e);
    }

    private void scrolled(ScrollEvent e) {
        if (e.isControlDown()) {
            double at = t(e.getX());
            pxPerSec = Math.max(8, Math.min(800, pxPerSec * (e.getDeltaY() > 0 ? 1.2 : 1 / 1.2)));
            scroll = Math.max(0, at - (e.getX() - HEADER) / pxPerSec);
        } else {
            double d = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
            scroll = Math.max(0, Math.min(Math.max(0, duration() - visibleSeconds() * 0.5), scroll - d / pxPerSec));
        }
        e.consume();
        redraw();
        if (onView != null) onView.run();
    }

    // ---- Zeichnen

    void redraw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#15151b"));
        g.fillRect(0, 0, w, h);
        g.setFont(Font.font(11));
        double t0 = scroll, t1 = t(w);
        double tracksTop = RULER + WAVE, bottom = tracksTop + show.tracks.size() * TRACK;

        // Song-Teile als Band unten im Lineal
        if (song != null) {
            List<Song.Part> parts = song.parts;
            for (int i = 0; i < parts.size(); i++) {
                Song.Part p = parts.get(i);
                double end = i + 1 < parts.size() ? parts.get(i + 1).time() : song.duration;
                if (end < t0 || p.time() > t1) continue;
                double a = Math.max(HEADER, x(p.time())), b = Math.min(w, x(end));
                g.setFill(partColor(p.section()).deriveColor(0, 1, 1, 0.3));
                g.fillRect(a, RULER - 10, b - a, 10);
                if (b - a > 60) {
                    g.setFill(Color.web("#c8c8d6"));
                    g.setTextAlign(TextAlignment.LEFT);
                    g.setTextBaseline(VPos.CENTER);
                    g.setFont(Font.font(9.5));
                    g.fillText(p.section().label, a + 4, RULER - 5);
                    g.setFont(Font.font(11));
                }
            }
        }
        // Zeitskala
        double step = niceStep(70 / pxPerSec);
        g.setStroke(Color.web("#3a3a48"));
        g.setFill(Color.web("#9a9aae"));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        for (double t = Math.floor(t0 / step) * step; t <= t1; t += step) {
            double x = x(t);
            if (x < HEADER) continue;
            g.strokeLine(x + 0.5, 0, x + 0.5, 7);
            g.fillText(format(t, step < 1), x + 3, 1);
        }
        // Beats
        if (song != null && pxPerSec > 12) {
            List<Double> beats = song.beats;
            for (int i = 0; i < beats.size(); i++) {
                double b = beats.get(i);
                if (b < t0 || b > t1) continue;
                double x = x(b);
                g.setStroke(i % 4 == 0 ? Color.web("#4a3a6a") : Color.web("#2a2a36"));
                g.strokeLine(x + 0.5, RULER, x + 0.5, Math.max(bottom, h));
            }
        }
        // Wellenform
        g.setFill(Color.web("#1b1b23"));
        g.fillRect(HEADER, RULER, w - HEADER, WAVE);
        if (song != null) {
            float[] peaks = song.peaks;
            double mid = RULER + WAVE / 2;
            double played = time.getAsDouble();
            for (double x = HEADER; x < w; x++) {
                int a = (int) (t(x) * Song.PEAKS_PER_SECOND), b = (int) (t(x + 1) * Song.PEAKS_PER_SECOND);
                if (a < 0 || a >= peaks.length) continue;
                float m = 0;
                for (int k = a; k <= Math.min(b, peaks.length - 1); k++) m = Math.max(m, peaks[k]);
                double hh = Math.max(1, m * (WAVE / 2 - 3));
                g.setFill(t(x) < played ? Color.web("#b48cff") : Color.web("#6a4fb8"));
                g.fillRect(x, mid - hh, 1, hh * 2);
            }
        } else {
            g.setFill(Color.web("#6f6f82"));
            g.setTextBaseline(VPos.CENTER);
            g.fillText("Kein Song geladen. Oben „Song laden“ wählen, oder ohne Musik eine feste Länge nutzen.", HEADER + 10, RULER + WAVE / 2);
        }
        // Spuren
        for (int i = 0; i < show.tracks.size(); i++) {
            Show.Track tr = show.tracks.get(i);
            double y = tracksTop + i * TRACK;
            g.setFill(tr == selectedTrack ? Color.web("#22202e") : i % 2 == 0 ? Color.web("#18181f") : Color.web("#1b1b22"));
            g.fillRect(HEADER, y, w - HEADER, TRACK);
            for (Show.Clip c : tr.clips) {
                if (c.end < t0 || c.start > t1) continue;
                drawClip(g, c, y, tr.mute);
            }
            g.setStroke(Color.web("#24242e"));
            g.strokeLine(HEADER, y + TRACK - 0.5, w, y + TRACK - 0.5);
        }
        // Markierungen: Linie durch alles, Fähnchen im Lineal
        for (Show.Marker m : show.markers) {
            if (m.time < t0 || m.time > t1) continue;
            double x = x(m.time);
            g.setStroke(Color.web("#ffd040").deriveColor(0, 1, 1, 0.55));
            g.setLineDashes(4, 4);
            g.strokeLine(x + 0.5, RULER, x + 0.5, Math.max(bottom, h));
            g.setLineDashes(null);
            g.setFill(Color.web("#ffd040"));
            g.fillPolygon(new double[]{x, x + 9, x + 9, x}, new double[]{9, 9, 19, 24}, 4);
            if (!m.name.isBlank()) {
                g.setFill(Color.web("#ffe590"));
                g.setTextAlign(TextAlignment.LEFT);
                g.setTextBaseline(VPos.CENTER);
                g.fillText(m.name, x + 12, 15);
            }
        }
        // Einrast-Hilfslinie
        if (!Double.isNaN(snapLine)) {
            double x = x(snapLine);
            g.setStroke(Color.web("#40e0ff"));
            g.setLineWidth(1.5);
            g.strokeLine(x, 0, x, h);
            g.setLineWidth(1);
        }
        // Rahmenauswahl
        if (drag == Drag.BAND) {
            double x1 = Math.min(bandX, bandX2), y1 = Math.min(bandY, bandY2);
            g.setFill(Color.web("#a020ff").deriveColor(0, 1, 1, 0.15));
            g.fillRect(x1, y1, Math.abs(bandX2 - bandX), Math.abs(bandY2 - bandY));
            g.setStroke(Color.web("#c08cff"));
            g.strokeRect(x1 + 0.5, y1 + 0.5, Math.abs(bandX2 - bandX), Math.abs(bandY2 - bandY));
        }
        // Mauszeiger-Hilfslinie mit Zeit
        if (hoverX > HEADER && drag == Drag.NONE) {
            g.setStroke(Color.web("#ffffff").deriveColor(0, 1, 1, 0.12));
            g.strokeLine(hoverX + 0.5, RULER, hoverX + 0.5, Math.max(bottom, h));
        }
        // Kopfspalte
        g.setFill(Color.web("#121217"));
        g.fillRect(0, 0, HEADER, h);
        g.setFill(Color.web("#9a9aae"));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("Zeit · Markierungen", 10, RULER / 2);
        g.fillText(song != null ? String.format("Song · %.0f BPM", song.bpm) : "Song", 10, RULER + WAVE / 2);
        for (int i = 0; i < show.tracks.size(); i++) {
            Show.Track tr = show.tracks.get(i);
            double y = tracksTop + i * TRACK;
            if (tr == selectedTrack) {
                g.setFill(Color.web("#a020ff").deriveColor(0, 1, 1, 0.35));
                g.fillRect(0, y, HEADER, TRACK);
            }
            g.setFill(Color.web(trackColor(i)));
            g.fillRect(0, y + 4, 3, TRACK - 8);
            g.setFill(tr.mute ? Color.web("#6f6f82") : Color.web("#e6e6ef"));
            g.fillText(clipText((i < 9 ? (i + 1) + "  " : "") + tr.name, HEADER - 40), 10, y + TRACK / 2);
            g.setFill(tr.mute ? Color.web("#ff5060") : Color.web("#6f6f82"));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(tr.mute ? "stumm" : "♪", HEADER - 15, y + TRACK / 2);
            g.setTextAlign(TextAlignment.LEFT);
        }
        if (show.tracks.isEmpty()) {
            g.setFill(Color.web("#6f6f82"));
            g.fillText("Noch keine Spur. Oben „+ Spur“ wählen.", HEADER + 10, tracksTop + TRACK / 2);
        }
        // Abspielkopf: Linie und Griff mit Zeit
        double now = time.getAsDouble(), px = x(now);
        if (px >= HEADER && px <= w) {
            g.setStroke(Color.web("#ff4060"));
            g.setLineWidth(2);
            g.strokeLine(px, RULER - 2, px, h);
            g.setLineWidth(1);
            String label = format(now, true);
            double bw = 50, bx = Math.min(w - bw, Math.max(HEADER, px - bw / 2));
            g.setFill(Color.web("#ff4060"));
            g.fillRoundRect(bx, 1, bw, 17, 6, 6);
            g.fillPolygon(new double[]{px - 6, px + 6, px}, new double[]{17, 17, RULER - 1}, 3);
            g.setFill(Color.WHITE);
            g.setFont(Font.font(null, FontWeight.BOLD, 10.5));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(label, bx + bw / 2, 9.5);
            g.setFont(Font.font(11));
        }
    }

    private void drawClip(GraphicsContext g, Show.Clip c, double y, boolean mute) {
        double a = Math.max(HEADER, x(c.start)), b = x(c.end), top = y + 4, height = TRACK - 8;
        if (b <= HEADER) return;
        boolean selected = selection.contains(c);
        Color color = clipColor(c);
        if (mute) color = color.desaturate().darker();
        g.setFill(color.deriveColor(0, 1, selected ? 0.75 : 0.55, 0.92));
        g.fillRoundRect(a, top, b - a, height, 6, 6);
        g.setFill(color.deriveColor(0, 1, 1, 0.55));
        double fi = Math.min(b - a, c.fadeIn * pxPerSec), fo = Math.min(b - a, c.fadeOut * pxPerSec);
        g.fillPolygon(new double[]{x(c.start), x(c.start) + fi, x(c.start) + fi}, new double[]{top + height, top, top + height}, 3);
        g.fillPolygon(new double[]{b - fo, b - fo, b}, new double[]{top, top + height, top + height}, 3);
        g.setFill(color);
        g.fillRect(a, top, b - a, 3);
        if (selected || c == recording) {
            g.setStroke(c == recording ? Color.web("#ff4060") : Color.WHITE);
            g.setLineWidth(c == selectedClip ? 2.2 : 1.4);
            g.strokeRoundRect(a + 1, top + 1, b - a - 2, height - 2, 6, 6);
            g.setLineWidth(1);
        }
        if (b - a > 34) {
            g.setFill(Color.web("#f0f0f6"));
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(clipText(label(c), b - a - 10), a + 6, top + height / 2 + 1);
        }
    }

    static String label(Show.Clip c) {
        Effect e = Effects.create(c.look.effect);
        String name = e == null ? c.look.effect : "static".equals(c.look.effect) ? "Farbe" : e.title();
        if (c.targets != null && !c.targets.isEmpty()) {
            long leds = c.targets.stream().filter(t -> t.startsWith("led:")).count();
            name += leds > 0 && leds == c.targets.size() ? " · " + leds + (leds == 1 ? " LED" : " LEDs") : " · eigene LEDs";
        }
        return name;
    }

    static Color clipColor(Show.Clip c) {
        Object color = c.look.params.get("color");
        if (color instanceof String hex) {
            try {
                return Ui.fx(Colors.parseHex(hex));
            } catch (RuntimeException ignored) {
            }
        }
        return Color.web("#8a5cf0");
    }

    private static Color partColor(MusicAnalyzer.Section s) {
        return switch (s) {
            case DROP -> Color.web("#ff3050");
            case BUILD -> Color.web("#ffa020");
            case CALM -> Color.web("#3080ff");
            case SILENCE -> Color.web("#606070");
            default -> Color.web("#a020ff");
        };
    }

    private static String clipText(String s, double width) {
        int max = (int) Math.max(0, width / 6.2);
        return s.length() <= max ? s : max <= 1 ? "" : s.substring(0, max - 1) + "…";
    }

    static double niceStep(double raw) {
        double[] steps = {0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300};
        for (double s : steps) if (s >= raw) return s;
        return 600;
    }

    static String format(double t, boolean fraction) {
        int m = (int) (t / 60);
        double s = t - m * 60;
        return fraction ? String.format("%d:%04.1f", m, s) : String.format("%d:%02d", m, (int) Math.round(s) % 60);
    }
}
