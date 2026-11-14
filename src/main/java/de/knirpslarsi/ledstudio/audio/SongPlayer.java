package de.knirpslarsi.ledstudio.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spielt einen Song ab und liefert die genaue Abspielzeit für die Show. Ohne Song läuft nur eine Uhr, damit sich
 * eine Show auch ohne Musik abspielen lässt.
 */
public final class SongPlayer {
    private static final Logger LOG = Logger.getLogger("Show");

    private volatile Song song;
    private volatile boolean playing;
    private volatile double position;
    private volatile double clockStart, clockOrigin;
    private Thread thread;
    private volatile SourceDataLine line;
    private volatile long lineStartFrame;
    private volatile double lineStartTime;
    /** Gesamtlänge ohne Song. */
    private volatile double length = 60;
    /** Lautstärke 0..1, wirkt sofort. */
    private volatile double volume = 1;

    public void setVolume(double v) {
        volume = Math.max(0, Math.min(1, v));
    }

    public double volume() {
        return volume;
    }

    public void setSong(Song song) {
        boolean was = playing;
        pause();
        this.song = song;
        position = 0;
        if (was && song != null) play();
    }

    public Song song() {
        return song;
    }

    public void setLength(double seconds) {
        length = Math.max(1, seconds);
    }

    public double duration() {
        Song s = song;
        return s != null ? s.duration : length;
    }

    public boolean playing() {
        return playing;
    }

    /** Aktuelle Zeit in Sekunden. */
    public double time() {
        if (!playing) return position;
        SourceDataLine l = line;
        Song s = song;
        if (l != null && s != null) return lineStartTime + (l.getLongFramePosition() - lineStartFrame) / (double) s.rate;
        return clockOrigin + (System.nanoTime() / 1e9 - clockStart);
    }

    public synchronized void play() {
        if (playing) return;
        if (position >= duration() - 0.01) position = 0;
        playing = true;
        Song s = song;
        if (s == null) {
            clockOrigin = position;
            clockStart = System.nanoTime() / 1e9;
            thread = new Thread(this::clockLoop, "show-uhr");
        } else {
            thread = new Thread(() -> audioLoop(s, position), "show-audio");
        }
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void pause() {
        if (!playing) return;
        position = Math.min(duration(), time());
        playing = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    public synchronized void seek(double seconds) {
        boolean was = playing;
        pause();
        position = Math.max(0, Math.min(duration(), seconds));
        if (was) play();
    }

    public void stop() {
        pause();
        position = 0;
    }

    private void clockLoop() {
        while (playing) {
            if (time() >= duration()) {
                position = duration();
                playing = false;
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void audioLoop(Song s, double from) {
        AudioFormat format = new AudioFormat(s.rate, 16, s.channels, true, false);
        try (SourceDataLine l = AudioSystem.getSourceDataLine(format)) {
            l.open(format, s.rate * s.channels * 2 / 8); // ~125 ms Puffer: kurze Verzögerung zu den LEDs
            l.start();
            int frame = (int) (from * s.rate);
            lineStartFrame = l.getLongFramePosition();
            lineStartTime = from;
            line = l;
            byte[] buf = new byte[4096 * s.channels];
            int framesPerChunk = buf.length / (2 * s.channels);
            int total = s.pcm.length / s.channels;
            while (playing && frame < total) {
                int n = Math.min(framesPerChunk, total - frame);
                // Lautstärke wie ein Fader: quadratisch, damit die untere Hälfte des Reglers nutzbar bleibt.
                double gain = volume * volume;
                for (int i = 0; i < n * s.channels; i++) {
                    short v = (short) Math.round(s.pcm[frame * s.channels + i] * gain);
                    buf[2 * i] = (byte) v;
                    buf[2 * i + 1] = (byte) (v >> 8);
                }
                l.write(buf, 0, n * 2 * s.channels);
                frame += n;
            }
            if (playing) l.drain();
            double end = lineStartTime + (l.getLongFramePosition() - lineStartFrame) / (double) s.rate;
            line = null;
            if (playing) {
                position = Math.min(s.duration, end);
                playing = false;
            }
            l.stop();
            l.flush();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Kein Audioausgang für die Show, laufe ohne Ton", e);
            line = null;
            clockOrigin = from;
            clockStart = System.nanoTime() / 1e9;
            clockLoop();
        }
    }
}
