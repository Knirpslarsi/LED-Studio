package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.layout.Targets;
import de.knirpslarsi.ledstudio.model.Fixture;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Kleine Ansicht des Aufbaus zum Auswählen einzelner LEDs. Klick wählt eine LED, Umschalt+Klick ein ganzes Bauteil.
 * Die LEDs zeigen dabei ihre aktuelle Farbe.
 */
final class LedPicker extends Region {
    private final Canvas canvas = new Canvas();
    private final Supplier<CompiledLayout> layout;
    private final Supplier<List<Fixture>> fixtures;
    private final Supplier<int[]> colors;
    private List<String> targets = new ArrayList<>();
    private Consumer<List<String>> onChange = t -> {
    };
    private double ox, oy, scale = 1;

    LedPicker(Supplier<CompiledLayout> layout, Supplier<List<Fixture>> fixtures, Supplier<int[]> colors) {
        this.layout = layout;
        this.fixtures = fixtures;
        this.colors = colors;
        getChildren().add(canvas);
        setPrefHeight(170);
        setMinHeight(120);
        canvas.setOnMouseClicked(this::clicked);
    }

    void edit(List<String> targets, Consumer<List<String>> onChange) {
        this.targets = new ArrayList<>(targets);
        this.onChange = onChange;
        redraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    /** Ist die LED im Ziel? */
    static boolean contains(List<String> targets, List<Fixture> fixtures, Led l) {
        if (l.fixture >= fixtures.size()) return false;
        String id = fixtures.get(l.fixture).id;
        return Targets.resolve(targets, fixtures).contains(id) || Targets.leds(targets).contains(id + "#" + l.k);
    }

    private void clicked(MouseEvent e) {
        CompiledLayout lay = layout.get();
        List<Fixture> fx = fixtures.get();
        Led hit = null;
        double best = 14 * 14;
        for (Led l : lay.leds) {
            double dx = ox + l.x * scale - e.getX(), dy = oy + l.y * scale - e.getY();
            if (dx * dx + dy * dy < best) {
                best = dx * dx + dy * dy;
                hit = l;
            }
        }
        if (hit == null || hit.fixture >= fx.size()) return;
        targets = Targets.toggleLed(targets, fx, lay, hit, e.isShiftDown());
        onChange.accept(targets);
        redraw();
    }

    void redraw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#121217"));
        g.fillRoundRect(0, 0, w, h, 8, 8);
        CompiledLayout lay = layout.get();
        if (lay == null || lay.leds.length == 0) {
            g.setFill(Color.web("#6f6f82"));
            g.fillText("Noch kein Layout angelegt.", 10, h / 2);
            return;
        }
        double pad = 14;
        scale = Math.min((w - 2 * pad) / Math.max(0.01, lay.width), (h - 2 * pad) / Math.max(0.01, lay.height));
        ox = (w - lay.width * scale) / 2;
        oy = (h - lay.height * scale) / 2;
        List<Fixture> fx = fixtures.get();
        Set<String> fixtureIds = new HashSet<>(Targets.resolve(targets, fx));
        Set<String> leds = Targets.leds(targets);
        int[] c = colors.get();
        double r = Math.max(4, Math.min(7, scale * 0.012));
        for (int i = 0; i < lay.leds.length; i++) {
            Led l = lay.leds[i];
            double x = ox + l.x * scale, y = oy + l.y * scale;
            boolean on = l.fixture < fx.size() && (fixtureIds.contains(fx.get(l.fixture).id) || leds.contains(fx.get(l.fixture).id + "#" + l.k));
            int color = c != null && i < c.length ? c[i] : 0;
            g.setFill(color == 0 ? Color.web("#2a2a34") : Ui.fx(color));
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
            if (on) {
                g.setStroke(Color.web("#ffffff"));
                g.setLineWidth(1.5);
                g.strokeOval(x - r - 2.5, y - r - 2.5, 2 * r + 5, 2 * r + 5);
            }
        }
    }
}
