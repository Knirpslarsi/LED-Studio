package de.knirpslarsi.ledstudio.ui;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/** Windows-Autostart über den Run-Schlüssel. Nutzt den Namen der alten Version und löst deren Eintrag so sauber ab. */
final class Autostart {
    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE = "LEDStudio";

    private Autostart() {
    }

    /** Nur die installierte EXE kann sich eintragen, nicht der Start aus der IDE. */
    static boolean available() {
        return System.getProperty("jpackage.app-path") != null;
    }

    static boolean enabled() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE)
                    && Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE).toLowerCase().contains("ledstudio.exe");
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void set(boolean on) {
        if (on) {
            String exe = System.getProperty("jpackage.app-path");
            if (exe == null) throw new IllegalStateException("Autostart geht nur mit der installierten EXE.");
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE, "\"" + exe + "\" --autostart");
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE);
        }
    }
}
