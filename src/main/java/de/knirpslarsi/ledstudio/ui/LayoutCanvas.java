package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.layout.Shapes;
import de.knirpslarsi.ledstudio.model.Fixture;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Zeichnet das Layout mit Live-Farben. Im Editor-Modus lassen sich Bauteile und einzelne LEDs bearbeiten. */
public final class LayoutCanvas extends Pane {
    private static final Color BG = Color.web("#101015"), GRID = Color.web("#1c1c25"), GRID_MAJOR = Color.web("#262632"),
            OUTLINE = Color.web("#3a3a4c"), ACCENT = Color.web("#a066ff"), WARN = Color.web("#ffb347"), MUTED = Color.web("#8f8fa3");
    private static final double SNAP = 10;

    private final AppCore core;
    private final boolean editable;
    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;

    private double camX, camY, zoom = 1;
    private boolean fitPending = true;
    private boolean hadFixtures;

    public final Set<String> selection = new LinkedHashSet<>();
    private final List<Runnable> selectionListeners = new ArrayList<>();
    private Set<String> highlight = Set.of();
    private java.util.function.Consumer<Fixture> fixtureClick;
    private boolean ledMode, showNumbers = true, clickIdentifies = true, snap = true;
    private Set<String> sharedIds = new HashSet<>();

    private enum Drag {NONE, PAN, MOVE, ROTATE, RESIZE, RUBBER, LED}

    private Drag drag = Drag.NONE;
    private double pressX, pressY, lastX, lastY;
    private boolean changed;
    private Fixture active;
    private int activeLed = -1;
    private double[] startCenter, startSize;
    private double startRotation, startAngle;
    private final java.util.Map<String, double[]> moveStart = new java.util.HashMap<>();

    public LayoutCanvas(AppCore core, boolean editable) {
        this.core = core;
        this.editable = editable;
        hadFixtures = !core.project.fixtures.isEmpty();
        getChildren().add(canvas);
        setMinSize(0, 0);
        timer = new AnimationTimer() {
            private long last;

            @Override
            public void handle(long now) {
                if (now - last < 16_000_000L || getScene() == null || !isVisible()) return;
                last = now;
                draw();
            }
        };
        timer.start();
        setFocusTraversable(editable);
        addEventHandler(ScrollEvent.SCROLL, this::onScroll);
        addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPress);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDrag);
        addEventHandler(MouseEvent.MOUSE_RELEASED, this::onRelease);
        if (editable) addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);
        core.onLayout(() -> {
            refreshIssues();
            // Die Vorschau zeigt immer alles; der Editor springt nur beim ersten Bauteil, damit Ziehen nicht ruckelt.
            if (!editable || !hadFixtures) fitPending = true;
            hadFixtures = !core.project.fixtures.isEmpty();
        });
        core.onDevices(this::refreshIssues);
        refreshIssues();
    }

    private void refreshIssues() {
        Set<String> shared = new HashSet<>();
        for (LayoutTools.Issue i : core.issues()) {
            if (i.kind() == LayoutTools.Issue.Kind.SHARED_ADDRESS) shared.addAll(i.fixtureIds());
        }
        sharedIds = shared;
    }

    @Override
    protected void layoutChildren() {
        if (canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            if (!editable) fitPending = true;
        }
    }

    /** Hebt Bauteile hervor (Ziel der gewählten Ebene). */
    public void setHighlight(Set<String> ids) {
        highlight = ids;
    }

    /** Nur Vorschau: Klick auf ein Bauteil. */
    public void onFixtureClick(java.util.function.Consumer<Fixture> c) {
        fixtureClick = c;
    }

    public void onSelection(Runnable r) {
        selectionListeners.add(r);
    }

    private void fireSelection() {
        selectionListeners.forEach(Runnable::run);
    }

    public void setLedMode(boolean v) {
        ledMode = v;
    }

    public boolean ledMode() {
        return ledMode;
    }

    public void setShowNumbers(boolean v) {
        showNumbers = v;
    }

    public void setClickIdentifies(boolean v) {
        clickIdentifies = v;
    }

    public void setSnap(boolean v) {
        snap = v;
    }

    public List<Fixture> selectedFixtures() {
        List<Fixture> out = new ArrayList<>();
        for (Fixture f : core.project.fixtures) if (selection.contains(f.id)) out.add(f);
        return out;
    }

    public void select(Fixture f, boolean add) {
        if (!add) selection.clear();
        if (f != null) selection.add(f.id);
        fireSelection();
    }

    public void fit() {
        fitPending = true;
    }

    // ---- Koordinaten

    private double sx(double wx) {
        return (wx - camX) * zoom + canvas.getWidth() / 2;
    }

    private double sy(double wy) {
        return (wy - camY) * zoom + canvas.getHeight() / 2;
    }

    private double wx(double sx) {
        return (sx - canvas.getWidth() / 2) / zoom + camX;
    }

    private double wy(double sy) {
        return (sy - canvas.getHeight() / 2) / zoom + camY;
    }

    private void doFit() {
        List<Fixture> list = core.project.fixtures;
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        fitPending = false;
        if (list.isEmpty()) {
            camX = camY = 0;
            zoom = 1;
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Fixture f : list) {
            double r = Math.max(f.width, f.height) / 2 + 12;
            minX = Math.min(minX, f.x - r);
            maxX = Math.max(maxX, f.x + r);
            minY = Math.min(minY, f.y - r);
            maxY = Math.max(maxY, f.y + r);
        }
        camX = (minX + maxX) / 2;
        camY = (minY + maxY) / 2;
        zoom = Math.max(0.1, Math.min(4, Math.min((w - 60) / (maxX - minX), (h - 60) / (maxY - minY))));
    }

    // ---- Zeichnen

    private void draw() {
        if (fitPending) doFit();
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(BG);
        g.fillRect(0, 0, w, h);
        if (editable) drawGrid(g, w, h);

        CompiledLayout layout = core.compiled();
        List<Fixture> fixtures = core.project.fixtures;
        if (layout.fanCount != fixtures.size()) return;
        int[] colors = core.engine.preview();
        boolean colorsValid = colors.length == layout.leds.length;
        double[] p = new double[2];

        for (int fi = 0; fi < fixtures.size(); fi++) drawFixtureBody(g, fixtures.get(fi), fi, layout.unresolved.contains(fi));

        double r = Math.max(editable ? 2.6 : 4.5, Math.min(9, 5.5 * zoom));
        // Erst der Schein, dann die LEDs, damit kein Bauteil das Licht eines anderen überdeckt.
        for (int i = 0; i < layout.leds.length; i++) {
            CompiledLayout.Led l = layout.leds[i];
            int c = colorsValid ? colors[i] : 0;
            if (c == 0 || l.skipped) continue;
            double x = sx(l.wx), y = sy(l.wy);
            int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
            double lum = Math.max(cr, Math.max(cg, cb)) / 255.0;
            g.setFill(Color.rgb(cr, cg, cb, 0.12 * lum));
            g.fillOval(x - r * 3.4, y - r * 3.4, r * 6.8, r * 6.8);
            g.setFill(Color.rgb(cr, cg, cb, 0.22 * lum));
            g.fillOval(x - r * 2, y - r * 2, r * 4, r * 4);
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI", Math.max(7, r * 0.95)));
        for (int i = 0; i < layout.leds.length; i++) {
            CompiledLayout.Led l = layout.leds[i];
            int c = colorsValid ? colors[i] : 0;
            double x = sx(l.wx), y = sy(l.wy);
            if (l.skipped) {
                g.setStroke(MUTED);
                g.setLineWidth(1);
                g.strokeLine(x - r, y - r, x + r, y + r);
                g.strokeLine(x - r, y + r, x + r, y - r);
                continue;
            }
            int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
            g.setFill(c == 0 ? Color.web("#22222c") : Color.rgb(cr, cg, cb));
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
            g.setStroke(l.device < 0 ? WARN : Color.web("#00000080"));
            g.setLineWidth(1);
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
            if (editable && showNumbers && (r >= 6 || ledMode)) {
                double lum = 0.3 * cr + 0.59 * cg + 0.11 * cb;
                g.setFill(lum > 130 ? Color.BLACK : Color.web("#c8c8d8"));
                g.fillText(Integer.toString(l.k + 1), x, y + r * 0.36);
            }
            if (editable && l.k == 0 && l.m > 1) {
                g.setStroke(Color.WHITE);
                g.setLineWidth(1.6);
                g.strokeOval(x - r - 2, y - r - 2, 2 * r + 4, 2 * r + 4);
                CompiledLayout.Led next = layout.leds[i + 1];
                drawArrow(g, x, y, sx(next.wx), sy(next.wy), r);
            }
        }
        for (Fixture f : fixtures) if (editable && selection.contains(f.id)) drawSelection(g, f, p);
        if (drag == Drag.RUBBER) {
            g.setStroke(ACCENT);
            g.setLineWidth(1);
            g.setLineDashes(4, 3);
            g.strokeRect(Math.min(pressX, lastX), Math.min(pressY, lastY), Math.abs(lastX - pressX), Math.abs(lastY - pressY));
            g.setLineDashes((double[]) null);
        }
        if (fixtures.isEmpty()) {
            g.setFill(MUTED);
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", 15));
            g.fillText(editable ? "Noch keine Bauteile.\nLinks eine Zone wählen und hinzufügen oder „Auto-Layout“ nutzen."
                    : "Noch kein Layout angelegt.\nUnter „Layout“ deine Lüfter anordnen, dann erscheinen die Effekte hier.", w / 2, h / 2);
        }
    }

    private void drawGrid(GraphicsContext g, double w, double h) {
        double step = zoom < 0.25 ? 100 : zoom < 0.7 ? 50 : 10;
        if (step * zoom < 6) return;
        double x0 = Math.floor(wx(0) / step) * step, y0 = Math.floor(wy(0) / step) * step;
        g.setLineWidth(1);
        for (double x = x0; sx(x) < w; x += step) {
            g.setStroke(Math.round(x) % 100 == 0 ? GRID_MAJOR : GRID);
            double s = Math.floor(sx(x)) + 0.5;
            g.strokeLine(s, 0, s, h);
        }
        for (double y = y0; sy(y) < h; y += step) {
            g.setStroke(Math.round(y) % 100 == 0 ? GRID_MAJOR : GRID);
            double s = Math.floor(sy(y)) + 0.5;
            g.strokeLine(0, s, w, s);
        }
    }

    private void drawArrow(GraphicsContext g, double x, double y, double nx, double ny, double r) {
        double dx = nx - x, dy = ny - y, len = Math.hypot(dx, dy);
        if (len < r * 2.5) return;
        dx /= len;
        dy /= len;
        double ax = x + dx * (r + 4), ay = y + dy * (r + 4), bx = ax + dx * Math.min(len * 0.45, 22), by = ay + dy * Math.min(len * 0.45, 22);
        g.setStroke(Color.WHITE);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineWidth(1.6);
        g.strokeLine(ax, ay, bx, by);
        g.strokeLine(bx, by, bx - dx * 5 - dy * 4, by - dy * 5 + dx * 4);
        g.strokeLine(bx, by, bx - dx * 5 + dy * 4, by - dy * 5 - dx * 4);
    }

    private void drawFixtureBody(GraphicsContext g, Fixture f, int chainIndex, boolean unresolved) {
        double x = sx(f.x), y = sy(f.y);
        boolean shared = sharedIds.contains(f.id);
        Color line = unresolved ? WARN : shared ? WARN : highlight.contains(f.id) ? ACCENT : OUTLINE;
        g.save();
        g.translate(x, y);
        g.rotate(f.rotation);
        double w = f.width * zoom, h = f.height * zoom;
        g.setStroke(line);
        g.setLineWidth(1.2);
        if (shared) g.setLineDashes(5, 4);
        switch (f.shape) {
            case RING, DUAL_RING -> {
                g.setFill(Color.web("#17171f"));
                g.fillOval(-w / 2, -h / 2, w, h);
                g.strokeOval(-w / 2, -h / 2, w, h);
                g.strokeOval(-w * 0.13, -h * 0.13, w * 0.26, h * 0.26);
            }
            case SQUARE -> {
                double arc = 2 * f.corner * Math.min(w, h);
                g.setFill(Color.web("#17171f"));
                g.fillRoundRect(-w / 2, -h / 2, w, h, arc, arc);
                g.strokeRoundRect(-w / 2, -h / 2, w, h, arc, arc);
            }
            case LINE -> g.strokeRoundRect(-w / 2 - 8, -8, w + 16, 16, 12, 12);
            case GRID, FREE -> g.strokeRect(-w / 2 - 8, -h / 2 - 8, w + 16, h + 16);
            case ARC -> g.strokeOval(-w / 2, -h / 2, w, h);
        }
        g.setLineDashes((double[]) null);
        g.restore();
        if (editable) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", 11));
            double half = Math.max(f.width, f.height) / 2 * zoom;
            double labelY = y + (f.shape == Fixture.Shape.LINE ? 26 : half + 20);
            g.setFill(unresolved ? WARN : Color.web("#b6b6c8"));
            String badge = shared ? " ⧉" : unresolved ? " ⚠" : "";
            // Name nur, wenn er neben die Nachbarn passt; sonst reicht die Nummer in der Kette.
            if (f.width * zoom > f.name.length() * 5.8) g.fillText((chainIndex + 1) + ". " + f.name + badge, x, labelY);
            else g.fillText((chainIndex + 1) + badge, x, y + 4);
        }
    }

    private void drawSelection(GraphicsContext g, Fixture f, double[] p) {
        double x = sx(f.x), y = sy(f.y), w = f.width * zoom, h = f.height * zoom;
        g.save();
        g.translate(x, y);
        g.rotate(f.rotation);
        g.setStroke(ACCENT);
        g.setLineWidth(1.5);
        g.strokeRect(-w / 2 - 10, -h / 2 - 10, w + 20, h + 20);
        if (!ledMode && selection.size() == 1) {
            g.setFill(ACCENT);
            g.strokeLine(0, -h / 2 - 10, 0, -h / 2 - 30);
            g.fillOval(-6, -h / 2 - 36, 12, 12);
            g.fillRect(w / 2 + 4, h / 2 + 4, 12, 12);
        }
        g.restore();
    }

    // ---- Treffer

    private double[] handlePos(Fixture f, boolean rotate) {
        double a = Math.toRadians(f.rotation), c = Math.cos(a), s = Math.sin(a);
        double lx = rotate ? 0 : f.width * zoom / 2 + 10;
        double ly = rotate ? -f.height * zoom / 2 - 30 : f.height * zoom / 2 + 10;
        return new double[]{sx(f.x) + lx * c - ly * s, sy(f.y) + lx * s + ly * c};
    }

    private Fixture hitFixture(double x, double y) {
        List<Fixture> list = core.project.fixtures;
        double[] p = new double[2];
        for (int i = list.size() - 1; i >= 0; i--) {
            Fixture f = list.get(i);
            Shapes.toLocal(f, wx(x), wy(y), p);
            double mx = 0.5 + 12 / Math.max(1, f.width * zoom), my = 0.5 + 12 / Math.max(1, f.height * zoom);
            if (Math.abs(p[0]) <= mx && Math.abs(p[1]) <= my) return f;
        }
        return null;
    }

    private int hitLed(double x, double y) {
        CompiledLayout.Led[] leds = core.compiled().leds;
        double best = Math.max(9, 8 * zoom);
        int found = -1;
        for (int i = 0; i < leds.length; i++) {
            double d = Math.hypot(sx(leds[i].wx) - x, sy(leds[i].wy) - y);
            if (d < best) {
                best = d;
                found = i;
            }
        }
        return found;
    }

    // ---- Maus

    private void onScroll(ScrollEvent e) {
        double before = zoom;
        double wx = wx(e.getX()), wy = wy(e.getY());
        zoom = Math.max(0.08, Math.min(8, zoom * Math.pow(1.0015, e.getDeltaY())));
        if (zoom != before) {
            camX = wx - (e.getX() - canvas.getWidth() / 2) / zoom;
            camY = wy - (e.getY() - canvas.getHeight() / 2) / zoom;
        }
        e.consume();
    }

    private double snap(double v, MouseEvent e) {
        return snap && !e.isAltDown() ? Math.round(v / SNAP) * SNAP : v;
    }

    private void onPress(MouseEvent e) {
        requestFocus();
        pressX = lastX = e.getX();
        pressY = lastY = e.getY();
        changed = false;
        active = null;
        activeLed = -1;
        if (e.getButton() == MouseButton.MIDDLE || (!editable && e.isPrimaryButtonDown())) {
            drag = Drag.PAN;
            return;
        }
        if (!editable) return;
        if (e.getButton() == MouseButton.SECONDARY) {
            Fixture f = hitFixture(e.getX(), e.getY());
            if (f == null) {
                drag = Drag.PAN;
            } else {
                drag = Drag.NONE;
                if (!selection.contains(f.id)) select(f, false);
                contextMenu(f, e);
            }
            return;
        }
        if (ledMode) {
            int led = hitLed(e.getX(), e.getY());
            if (led >= 0) {
                activeLed = led;
                active = core.project.fixtures.get(core.compiled().leds[led].fixture);
                drag = Drag.LED;
                if (!selection.contains(active.id)) select(active, false);
                return;
            }
        }
        if (selection.size() == 1 && !ledMode) {
            Fixture sel = selectedFixtures().stream().findFirst().orElse(null);
            if (sel != null) {
                double[] rot = handlePos(sel, true), res = handlePos(sel, false);
                if (Math.hypot(rot[0] - e.getX(), rot[1] - e.getY()) < 11) {
                    drag = Drag.ROTATE;
                    active = sel;
                    startRotation = sel.rotation;
                    startAngle = Math.toDegrees(Math.atan2(wy(e.getY()) - sel.y, wx(e.getX()) - sel.x));
                    return;
                }
                if (Math.hypot(res[0] - e.getX(), res[1] - e.getY()) < 12) {
                    drag = Drag.RESIZE;
                    active = sel;
                    startSize = new double[]{sel.width, sel.height};
                    return;
                }
            }
        }
        Fixture f = hitFixture(e.getX(), e.getY());
        if (f == null) {
            if (!e.isShiftDown()) select(null, false);
            drag = Drag.RUBBER;
            return;
        }
        if (e.isShiftDown()) {
            if (!selection.remove(f.id)) selection.add(f.id);
            fireSelection();
        } else if (!selection.contains(f.id)) {
            select(f, false);
        }
        active = f;
        drag = Drag.MOVE;
        moveStart.clear();
        for (Fixture s : selectedFixtures()) moveStart.put(s.id, new double[]{s.x, s.y});
        startCenter = new double[]{f.x, f.y};
    }

    private void beginChange() {
        if (!changed) {
            changed = true;
            core.snapshot();
        }
    }

    private void onDrag(MouseEvent e) {
        double x = e.getX(), y = e.getY();
        switch (drag) {
            case PAN -> {
                camX -= (x - lastX) / zoom;
                camY -= (y - lastY) / zoom;
            }
            case MOVE -> {
                if (!changed && Math.hypot(x - pressX, y - pressY) < 4) break;
                beginChange();
                double dx = (x - pressX) / zoom, dy = (y - pressY) / zoom;
                double targetX = snap(startCenter[0] + dx, e), targetY = snap(startCenter[1] + dy, e);
                dx = targetX - startCenter[0];
                dy = targetY - startCenter[1];
                for (Fixture s : selectedFixtures()) {
                    double[] st = moveStart.get(s.id);
                    if (st != null) {
                        s.x = st[0] + dx;
                        s.y = st[1] + dy;
                    }
                }
                core.layoutChanged();
            }
            case ROTATE -> {
                beginChange();
                double a = Math.toDegrees(Math.atan2(wy(y) - active.y, wx(x) - active.x));
                double rot = startRotation + a - startAngle;
                if (!e.isAltDown()) rot = Math.round(rot / 15) * 15;
                active.rotation = ((rot % 360) + 360) % 360;
                core.layoutChanged();
            }
            case RESIZE -> {
                beginChange();
                double[] p = new double[2];
                Shapes.toLocal(active, wx(x), wy(y), p);
                double w = Math.abs(p[0]) * active.width * 2, h = Math.abs(p[1]) * active.height * 2;
                boolean uniform = (active.shape == Fixture.Shape.RING || active.shape == Fixture.Shape.DUAL_RING) && !e.isShiftDown();
                if (uniform) w = h = Math.max(w, h);
                active.width = Math.max(10, snap(w, e));
                active.height = active.shape == Fixture.Shape.LINE ? active.height : Math.max(10, snap(h, e));
                core.layoutChanged();
            }
            case LED -> {
                if (!changed && Math.hypot(x - pressX, y - pressY) < 4) break;
                beginChange();
                makeFree(active);
                int k = core.compiled().leds[activeLed].k;
                double[] p = new double[2];
                Shapes.toLocal(active, wx(x), wy(y), p);
                active.points.get(k)[0] = p[0];
                active.points.get(k)[1] = p[1];
                core.layoutChanged();
            }
            default -> {
            }
        }
        lastX = x;
        lastY = y;
    }

    /** Wandelt die aktuelle Form in frei verschiebbare Punkte um. */
    public static void makeFree(Fixture f) {
        if (f.shape == Fixture.Shape.FREE) return;
        double[][] local = Shapes.local(f);
        f.points.clear();
        for (double[] p : local) f.points.add(p.clone());
        f.shape = Fixture.Shape.FREE;
    }

    private void onRelease(MouseEvent e) {
        if (!editable && fixtureClick != null && e.getButton() == MouseButton.PRIMARY
                && Math.hypot(e.getX() - pressX, e.getY() - pressY) < 4) {
            Fixture f = hitFixture(e.getX(), e.getY());
            if (f != null) fixtureClick.accept(f);
        }
        if (drag == Drag.RUBBER) {
            double x0 = wx(Math.min(pressX, e.getX())), x1 = wx(Math.max(pressX, e.getX()));
            double y0 = wy(Math.min(pressY, e.getY())), y1 = wy(Math.max(pressY, e.getY()));
            if (Math.abs(pressX - e.getX()) > 3 || Math.abs(pressY - e.getY()) > 3) {
                for (Fixture f : core.project.fixtures) {
                    if (f.x >= x0 && f.x <= x1 && f.y >= y0 && f.y <= y1) selection.add(f.id);
                }
                fireSelection();
            }
        } else if (drag == Drag.LED && !changed && activeLed >= 0) {
            if (e.isControlDown()) toggleSkip(activeLed);
            else if (clickIdentifies) core.engine.identify(Set.of(activeLed), 2500);
        } else if (drag == Drag.MOVE && !changed && clickIdentifies && active != null) {
            identifyFixture(active);
        }
        if (changed) fireSelection();
        drag = Drag.NONE;
    }

    private void toggleSkip(int led) {
        CompiledLayout.Led l = core.compiled().leds[led];
        Fixture f = core.project.fixtures.get(l.fixture);
        core.snapshot();
        int number = l.k + 1;
        if (!f.skip.remove(Integer.valueOf(number))) f.skip.add(number);
        core.layoutChanged();
        fireSelection();
    }

    /** Lässt das Bauteil real weiß leuchten, alles andere bleibt kurz dunkel. */
    public void identifyFixture(Fixture f) {
        int index = core.project.fixtures.indexOf(f);
        CompiledLayout layout = core.compiled();
        if (index < 0 || index >= layout.fixtureStart.length) return;
        Set<Integer> leds = new HashSet<>();
        for (int i = layout.fixtureStart[index]; i < layout.fixtureStart[index] + f.count && i < layout.leds.length; i++) leds.add(i);
        core.engine.identify(leds, 2500);
    }

    private void contextMenu(Fixture f, MouseEvent e) {
        ContextMenu m = new ContextMenu();
        MenuItem id = new MenuItem("Real aufleuchten lassen");
        id.setOnAction(a -> identifyFixture(f));
        MenuItem dup = new MenuItem("Kopie mit nächsten Adressen");
        dup.setOnAction(a -> duplicate(false));
        MenuItem mirror = new MenuItem("Zweites Bauteil auf denselben Adressen (Splitter)");
        mirror.setOnAction(a -> duplicate(true));
        MenuItem turn = new MenuItem("90° drehen");
        turn.setOnAction(a -> {
            core.snapshot();
            selectedFixtures().forEach(s -> s.rotation = (s.rotation + 90) % 360);
            core.layoutChanged();
            fireSelection();
        });
        MenuItem flip = new MenuItem("Spiegeln");
        flip.setOnAction(a -> {
            core.snapshot();
            selectedFixtures().forEach(s -> s.flipX = !s.flipX);
            core.layoutChanged();
            fireSelection();
        });
        MenuItem del = new MenuItem("Entfernen");
        del.setOnAction(a -> deleteSelected());
        m.getItems().addAll(id, new SeparatorMenuItem(), dup, mirror, new SeparatorMenuItem(), turn, flip, new SeparatorMenuItem(), del);
        m.show(this, e.getScreenX(), e.getScreenY());
    }

    public void duplicate(boolean sameAddress) {
        List<Fixture> sel = selectedFixtures();
        if (sel.isEmpty()) return;
        core.snapshot();
        selection.clear();
        for (Fixture f : sel) {
            Fixture c = f.copy();
            c.x += Math.max(f.width, 80) + 20;
            if (sameAddress) {
                c.name = f.name + " (Splitter)";
            } else {
                c.start = f.start + f.count;
                c.name = f.name + " Kopie";
            }
            core.project.fixtures.add(c);
            selection.add(c.id);
        }
        core.layoutChanged();
        fireSelection();
    }

    public void deleteSelected() {
        if (selection.isEmpty()) return;
        core.snapshot();
        core.project.fixtures.removeIf(f -> selection.contains(f.id));
        selection.clear();
        core.layoutChanged();
        fireSelection();
    }

    private void onKey(KeyEvent e) {
        boolean ctrl = e.isControlDown();
        if (e.getCode() == KeyCode.DELETE) {
            deleteSelected();
        } else if (ctrl && e.getCode() == KeyCode.Z) {
            core.undo();
            fireSelection();
        } else if (ctrl && e.getCode() == KeyCode.Y) {
            core.redo();
            fireSelection();
        } else if (ctrl && e.getCode() == KeyCode.D) {
            duplicate(false);
        } else if (ctrl && e.getCode() == KeyCode.A) {
            core.project.fixtures.forEach(f -> selection.add(f.id));
            fireSelection();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            select(null, false);
        } else if (e.getCode().isArrowKey() && !selection.isEmpty()) {
            double step = e.isShiftDown() ? 10 : 1;
            double dx = e.getCode() == KeyCode.LEFT ? -step : e.getCode() == KeyCode.RIGHT ? step : 0;
            double dy = e.getCode() == KeyCode.UP ? -step : e.getCode() == KeyCode.DOWN ? step : 0;
            core.snapshot();
            selectedFixtures().forEach(f -> {
                f.x += dx;
                f.y += dy;
            });
            core.layoutChanged();
            fireSelection();
        } else {
            return;
        }
        e.consume();
    }
}
