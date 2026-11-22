package de.knirpslarsi.ledstudio.openrgb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Zwei Quellen als eine: OpenRGB (oder die Demo) vorne, zusätzliche Geräte wie WLED dahinter. Die Nummern der
 * zusätzlichen Geräte werden hinter die von OpenRGB geschoben. Ohne OpenRGB laufen nur die zusätzlichen.
 */
public final class MultiBackend implements LedBackend {
    private final LedBackend primary;
    private final LedBackend extra;
    private volatile List<Controller> list = List.of();
    private volatile int offset;

    public MultiBackend(LedBackend primary, LedBackend extra) {
        this.primary = primary;
        this.extra = extra;
        rebuild();
    }

    public boolean hasPrimary() {
        return primary != null;
    }

    private void rebuild() {
        List<Controller> out = new ArrayList<>(primary == null ? List.of() : primary.controllers());
        int base = out.size();
        for (Controller c : extra.controllers()) {
            out.add(new Controller(base + c.index(), c.type(), c.name(), c.vendor(), c.description(), c.version(), c.serial(),
                    c.location(), c.activeMode(), c.modes(), c.zones(), c.ledNames()));
        }
        offset = base;
        list = List.copyOf(out);
    }

    private boolean isExtra(int device) {
        return device >= offset;
    }

    @Override
    public List<Controller> controllers() {
        return list;
    }

    @Override
    public List<Controller> refresh() throws IOException {
        if (primary != null) primary.refresh();
        extra.refresh();
        rebuild();
        return list;
    }

    @Override
    public void enterDirectMode(int device) throws IOException {
        if (isExtra(device)) extra.enterDirectMode(device - offset);
        else if (primary != null) primary.enterDirectMode(device);
    }

    @Override
    public void setLeds(int device, int[] colors) throws IOException {
        if (isExtra(device)) extra.setLeds(device - offset, colors);
        else if (primary != null) primary.setLeds(device, colors);
    }

    @Override
    public void resizeZone(int device, int zone, int size) throws IOException {
        if (isExtra(device)) extra.resizeZone(device - offset, zone, size);
        else if (primary != null) primary.resizeZone(device, zone, size);
    }

    @Override
    public boolean supportsSegments() {
        return primary != null && primary.supportsSegments();
    }

    @Override
    public void clearSegments(int device, int zone) throws IOException {
        if (isExtra(device)) extra.clearSegments(device - offset, zone);
        else if (primary != null) primary.clearSegments(device, zone);
    }

    @Override
    public void addSegment(int device, int zone, String name, int start, int count) throws IOException {
        if (isExtra(device)) extra.addSegment(device - offset, zone, name, start, count);
        else if (primary != null) primary.addSegment(device, zone, name, start, count);
    }

    @Override
    public void rescan() throws IOException {
        if (primary != null) primary.rescan();
    }

    @Override
    public boolean consumeDeviceListChanged() {
        return primary != null && primary.consumeDeviceListChanged();
    }

    @Override
    public boolean alive() {
        return primary == null || primary.alive();
    }

    @Override
    public void close() {
        if (primary != null) primary.close();
        extra.close();
    }
}
