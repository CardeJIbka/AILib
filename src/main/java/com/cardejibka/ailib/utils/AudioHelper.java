package com.cardejibka.ailib.utils;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class AudioHelper {

    /**
     * Сохраняет байты в WAV-файл (перезаписывает при наличии) и воспроизводит звук.
     */
    public static void playAndSave(byte[] wavBytes, Path savePath) {
        if (wavBytes == null || wavBytes.length == 0) return;
        try {
            Files.write(savePath, wavBytes);

            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes));
            Clip clip = AudioSystem.getClip();
            clip.open(audioInputStream);
            clip.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Записывает звук с микрофона (16kHz, 16-bit, Mono PCM) в указанный файл.
     */
    public static void recordMic(Path outputPath, int durationSeconds) throws Exception {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            throw new Exception("Микрофон не найден или не поддерживается!");
        }

        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        Thread recordingThread = new Thread(() -> {
            try (AudioInputStream ais = new AudioInputStream(line)) {
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputPath.toFile());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        recordingThread.start();

        Thread.sleep(durationSeconds * 1000L);
        line.stop();
        line.close();
    }
}