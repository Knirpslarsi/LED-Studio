package de.knirpslarsi.ledstudio.openrgb;

import java.io.IOException;
import java.util.List;

/** Abstraktion über OpenRGB, damit die App auch ohne Hardware läuft (Demo, Tests). */
public interface LedBackend extends AutoCloseable {
    List<Controller> controllers();

    /** Liest die Geräteliste neu vom Server. */
    List<Controller> refresh() throws IOException;

    void enterDirectMode(int device) throws IOException;

    /** colors: 0xRRGGBB, eine Farbe pro LED des Geräts. */
    void setLeds(int device, int[] colors) throws IOException;

    void resizeZone(int device, int zone, int size) throws IOException;

    boolean supportsSegments();

    void clearSegments(int device, int zone) throws IOException;

    void addSegment(int device, int zone, String name, int start, int count) throws IOException;

    void rescan() throws IOException;

    /** True, wenn der Server seit dem letzten Aufruf eine Listenänderung gemeldet hat. */
    boolean consumeDeviceListChanged();

    /** False, sobald die Verbindung abgerissen ist. */
    default boolean alive() {
        return true;
    }

    @Override
    void close();
}
