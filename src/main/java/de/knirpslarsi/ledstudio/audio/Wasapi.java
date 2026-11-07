package de.knirpslarsi.ledstudio.audio;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimaler WASAPI-Zugriff. Java Sound kann keinen PC-Sound (Loopback) aufnehmen,
 * deshalb werden die COM-Schnittstellen direkt über ihre vtable aufgerufen.
 */
final class Wasapi {
    private static final Guid.GUID CLSID_ENUM = new Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final Guid.GUID IID_ENUM = new Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final Guid.GUID IID_AUDIO_CLIENT = new Guid.GUID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}");
    private static final Guid.GUID IID_CAPTURE = new Guid.GUID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}");
    private static final Guid.GUID PKEY_NAME = new Guid.GUID("{a45c254e-df1c-4efd-8020-67d146a850e0}");
    private static final int CLSCTX_ALL = 23, ERENDER = 0, ECAPTURE = 1, EALL = 2, ACTIVE = 1, LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 2;

    private Wasapi() {
    }

    private static int call(Pointer self, int slot, Object... args) {
        Pointer vtable = self.getPointer(0);
        Function f = Function.getFunction(vtable.getPointer((long) slot * Native.POINTER_SIZE));
        Object[] all = new Object[args.length + 1];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " fehlgeschlagen (0x" + Integer.toHexString(hr) + ")");
    }

    private static void release(Pointer p) {
        if (p != null) call(p, 2);
    }

    static void comInit() {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
    }

    private static Pointer enumerator() {
        PointerByReference out = new PointerByReference();
        check(Ole32.INSTANCE.CoCreateInstance(CLSID_ENUM, null, CLSCTX_ALL, IID_ENUM, out).intValue(), "Audiogeräte öffnen");
        return out.getValue();
    }

    private static String idOf(Pointer device) {
        PointerByReference id = new PointerByReference();
        check(call(device, 5, id), "Geräte-ID lesen");
        String text = id.getValue().getWideString(0);
        Ole32.INSTANCE.CoTaskMemFree(id.getValue());
        return text;
    }

    private static String nameOf(Pointer device) {
        PointerByReference store = new PointerByReference();
        if (call(device, 4, 0 /* STGM_READ */, store) < 0) return "Audiogerät";
        try {
            Memory key = new Memory(20);
            key.write(0, PKEY_NAME.toByteArray(), 0, 16);
            key.setInt(16, 14);
            Memory value = new Memory(24);
            value.clear();
            if (call(store.getValue(), 5, key, value) < 0) return "Audiogerät";
            Pointer text = value.getPointer(8);
            return text == null ? "Audiogerät" : text.getWideString(0);
        } finally {
            release(store.getValue());
        }
    }

    /** id → (Loopback?, Name). */
    static Map<String, String> devices() {
        comInit();
        Map<String, String> out = new LinkedHashMap<>();
        Pointer en = enumerator();
        try {
            for (int flow : new int[]{ERENDER, ECAPTURE}) {
                PointerByReference col = new PointerByReference();
                check(call(en, 3, flow, ACTIVE, col), "Audiogeräte auflisten");
                IntByReference count = new IntByReference();
                check(call(col.getValue(), 3, count), "Audiogeräte zählen");
                for (int i = 0; i < count.getValue(); i++) {
                    PointerByReference dev = new PointerByReference();
                    if (call(col.getValue(), 4, i, dev) < 0) continue;
                    out.put(idOf(dev.getValue()), (flow == ERENDER ? "PC-Sound · " : "Mikrofon · ") + nameOf(dev.getValue()));
                    release(dev.getValue());
                }
                release(col.getValue());
            }
        } finally {
            release(en);
        }
        return out;
    }

    /** Offene Aufnahme. Liefert Stereo-/Mehrkanal-Frames als float. */
    static final class Capture implements AutoCloseable {
        private final Pointer client, capture;
        final int channels, rate;
        final String name;
        private final boolean isFloat;
        private final int bits, blockAlign;

        Capture(String deviceId) {
            comInit();
            Pointer en = enumerator();
            Pointer device = null;
            try {
                PointerByReference dev = new PointerByReference();
                if ("default".equals(deviceId)) {
                    check(call(en, 4, ERENDER, 1 /* eMultimedia */, dev), "Standardausgabe finden");
                } else {
                    Memory wide = new Memory((deviceId.length() + 1) * 2L);
                    wide.setWideString(0, deviceId);
                    check(call(en, 5, wide, dev), "Audiogerät finden");
                }
                device = dev.getValue();
                name = nameOf(device);
                boolean loopback = isRender(en, idOf(device));

                PointerByReference ac = new PointerByReference();
                check(call(device, 3, new Guid.GUID.ByReference(IID_AUDIO_CLIENT), CLSCTX_ALL, null, ac), "Audio aktivieren");
                client = ac.getValue();
                PointerByReference fmt = new PointerByReference();
                check(call(client, 8, fmt), "Audioformat lesen");
                Pointer f = fmt.getValue();
                int tag = Short.toUnsignedInt(f.getShort(0));
                channels = Short.toUnsignedInt(f.getShort(2));
                rate = f.getInt(4);
                blockAlign = Short.toUnsignedInt(f.getShort(12));
                bits = Short.toUnsignedInt(f.getShort(14));
                // WAVE_FORMAT_IEEE_FLOAT = 3; bei EXTENSIBLE steht der Typ in der SubFormat-GUID.
                isFloat = tag == 3 || (tag == 0xFFFE && f.getInt(24) == 3);
                long duration = 2_000_000L; // 200 ms in 100-ns-Schritten
                check(call(client, 3, 0, loopback ? LOOPBACK : 0, duration, 0L, f, null), "Audioaufnahme starten");
                Ole32.INSTANCE.CoTaskMemFree(f);
                PointerByReference cc = new PointerByReference();
                check(call(client, 14, new Guid.GUID.ByReference(IID_CAPTURE), cc), "Aufnahmedienst holen");
                capture = cc.getValue();
                check(call(client, 10), "Aufnahme starten");
            } finally {
                release(device);
                release(en);
            }
        }

        private static boolean isRender(Pointer en, String id) {
            // Render-Endpunkte haben die Kennung {0.0.0.*}, Aufnahmegeräte {0.0.1.*}.
            return id.startsWith("{0.0.0.");
        }

        /** Hängt verfügbare Samples an out an (interleaved). Gibt die Zahl gelesener Frames zurück. */
        int read(ArrayList<float[]> out) {
            int total = 0;
            IntByReference next = new IntByReference();
            while (true) {
                check(call(capture, 5, next), "Audiopaket prüfen");
                if (next.getValue() == 0) return total;
                PointerByReference data = new PointerByReference();
                IntByReference frames = new IntByReference();
                IntByReference flags = new IntByReference();
                LongByReference pos = new LongByReference(), qpc = new LongByReference();
                check(call(capture, 3, data, frames, flags, pos, qpc), "Audiodaten lesen");
                int n = frames.getValue();
                float[] samples = new float[n * channels];
                if ((flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) == 0 && n > 0) {
                    Pointer p = data.getValue();
                    if (isFloat && bits == 32) {
                        p.read(0, samples, 0, samples.length);
                    } else if (bits == 16) {
                        short[] s = p.getShortArray(0, samples.length);
                        for (int i = 0; i < s.length; i++) samples[i] = s[i] / 32768f;
                    } else if (bits == 32) {
                        int[] s = p.getIntArray(0, samples.length);
                        for (int i = 0; i < s.length; i++) samples[i] = s[i] / 2147483648f;
                    } else if (bits == 24) {
                        int step = blockAlign / Math.max(1, channels);
                        byte[] raw = p.getByteArray(0, n * blockAlign);
                        for (int i = 0; i < samples.length; i++) {
                            int o = i * step;
                            int v = (raw[o] & 255) | (raw[o + 1] & 255) << 8 | raw[o + 2] << 16;
                            samples[i] = v / 8388608f;
                        }
                    }
                }
                check(call(capture, 4, n), "Audiopaket freigeben");
                out.add(samples);
                total += n;
            }
        }

        @Override
        public void close() {
            try {
                call(client, 11);
            } finally {
                release(capture);
                release(client);
            }
        }
    }
}
