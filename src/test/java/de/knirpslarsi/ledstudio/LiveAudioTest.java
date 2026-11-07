package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.model.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft WASAPI am echten Rechner. Aktivieren mit LEDSTUDIO_LIVE=1. */
class LiveAudioTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "LEDSTUDIO_LIVE", matches = "1")
    void capturesFromDefaultOutput() throws Exception {
        Map<String, String> devices = AudioEngine.devices();
        System.out.println("Audioquellen: " + devices.values());
        assertTrue(devices.size() > 1, "mindestens ein echtes Gerät neben dem Standardeintrag");
        Project.Audio cfg = new Project.Audio();
        cfg.enabled = true;
        AudioEngine audio = new AudioEngine(() -> cfg);
        audio.start();
        long end = System.currentTimeMillis() + 4000;
        while (!audio.status.startsWith("Audio aktiv") && !audio.status.startsWith("Audiofehler") && System.currentTimeMillis() < end) Thread.sleep(20);
        Thread.sleep(1500);
        System.out.println("Status: " + audio.status + ", Pegel: " + Arrays.toString(audio.values) + ", Zeitstempel: " + audio.stamp);
        assertTrue(audio.status.startsWith("Audio aktiv"), audio.status);
        audio.stop();
    }
}
