package de.knirpslarsi.ledstudio.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ein Song für die Show-Zeitleiste, komplett in den Speicher dekodiert: 16-Bit-PCM zum Abspielen, eine Wellenform
 * zum Zeichnen und die erkannten Beats und Song-Teile zum Einrasten.
 */
public final class Song {
    /** Auflösung der Wellenform: Werte pro Sekunde. */
    public static final int PEAKS_PER_SECOND = 100;

    public final Path file;
    public final int rate, channels;
    /** Abwechselnd die Kanäle, 16 Bit. */
    public final short[] pcm;
    public final double duration;
    /** Lauteste Stelle je 1/100 s, 0..1. */
    public final float[] peaks;
    /** Erkannte Schläge in Sekunden und das Tempo. */
    public volatile List<Double> beats = List.of();
    public volatile double bpm;
    /** Erkannte Song-Teile: Startzeit und Abschnitt. */
    public volatile List<Part> parts = List.of();

    public record Part(double time, MusicAnalyzer.Section section) {
    }

    private Song(Path file, int rate, int channels, short[] pcm) {
        this.file = file;
        this.rate = rate;
        this.channels = Math.max(1, channels);
        this.pcm = pcm;
        this.duration = pcm.length / (double) this.channels / rate;
        int n = (int) Math.ceil(duration * PEAKS_PER_SECOND);
        peaks = new float[n];
        int frames = pcm.length / this.channels;
        for (int f = 0; f < frames; f++) {
            int k = (int) ((long) f * PEAKS_PER_SECOND / rate);
            if (k >= n) break;
            for (int c = 0; c < this.channels; c++) {
                float v = Math.abs(pcm[f * this.channels + c]) / 32768f;
                if (v > peaks[k]) peaks[k] = v;
            }
        }
    }

    /** Lädt MP3 oder WAV (auch AIFF/AU, was Java selbst kann). */
    public static Song load(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") ? mp3(file) : sampled(file);
    }

    private static Song mp3(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream bits = new Bitstream(in);
            Decoder decoder = new Decoder();
            ShortList out = new ShortList(1 << 20);
            int rate = 44100, channels = 2;
            Header header;
            while ((header = bits.readFrame()) != null) {
                SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bits);
                rate = decoder.getOutputFrequency();
                channels = decoder.getOutputChannels();
                out.add(buffer.getBuffer(), buffer.getBufferLength());
                bits.closeFrame();
            }
            if (out.size == 0) throw new IOException("Keine MP3-Daten gefunden");
            return new Song(file, rate, channels, out.toArray());
        } catch (javazoom.jl.decoder.JavaLayerException e) {
            throw new IOException("MP3 nicht lesbar: " + e.getMessage(), e);
        }
    }

    private static Song sampled(Path file) throws IOException {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat src = raw.getFormat();
            AudioFormat pcm16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
                    src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm16, raw)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                byte[] b = bytes.toByteArray();
                short[] s = new short[b.length / 2];
                for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
                return new Song(file, (int) src.getSampleRate(), src.getChannels(), s);
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException("Format nicht unterstützt. Bitte MP3 oder WAV verwenden.", e);
        }
    }

    /**
     * Hört den ganzen Song einmal mit der Musik-Analyse durch und merkt sich Beats und Song-Teile. Dauert je nach
     * Länge ein, zwei Sekunden; am besten im Hintergrund aufrufen.
     */
    public void analyse() {
        MusicAnalyzer a = new MusicAnalyzer();
        int window = 2048, hop = window / 2, frames = pcm.length / channels;
        float[] mono = new float[window];
        List<Double> found = new ArrayList<>();
        List<Part> sections = new ArrayList<>();
        long lastBeat = Long.MIN_VALUE;
        MusicAnalyzer.Section lastSection = null;
        for (int start = 0; start + window <= frames; start += hop) {
            for (int i = 0; i < window; i++) {
                int f = start + i;
                float sum = 0;
                for (int c = 0; c < channels; c++) sum += pcm[f * channels + c];
                mono[i] = sum / channels / 32768f;
            }
            double stamp = (start + window) / (double) rate;
            a.feed(AudioEngine.analyse(mono, 1, rate), stamp, hop / (double) rate);
            MusicAnalyzer.Scene s = a.scene();
            if (s.confidence() > 0.3 && s.beatNumber() != lastBeat) {
                lastBeat = s.beatNumber();
                double t = s.beatTime() - window / 2.0 / rate; // Fenstermitte statt -ende
                if (t >= 0 && (found.isEmpty() || t - found.get(found.size() - 1) > 0.15)) found.add(t);
            }
            if (s.section() != lastSection) {
                lastSection = s.section();
                sections.add(new Part(Math.max(0, stamp - window / (double) rate), s.section()));
            }
            bpm = s.bpm();
        }
        beats = List.copyOf(found);
        parts = List.copyOf(sections);
    }

    /** Wachsendes short-Array ohne Boxing. */
    private static final class ShortList {
        short[] data;
        int size;

        ShortList(int capacity) {
            data = new short[capacity];
        }

        void add(short[] src, int len) {
            if (size + len > data.length) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + len));
            System.arraycopy(src, 0, data, size, len);
            size += len;
        }

        short[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
