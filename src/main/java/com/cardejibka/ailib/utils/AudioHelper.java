package com.cardejibka.ailib.utils;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class AudioHelper {

    public static boolean isClientEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    public static boolean playAndSave(byte[] wavBytes, Path savePath) {
        if (wavBytes == null || wavBytes.length == 0) return false;

        try {
            Files.write(savePath, wavBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        if (!isClientEnvironment()) return true;

        try {
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes));
            Clip clip = AudioSystem.getClip();
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                    try {
                        audioInputStream.close();
                    } catch (Exception ignored) {
                    }
                }
            });
            clip.open(audioInputStream);
            clip.start();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void recordMic(Path outputPath, int durationSeconds) throws Exception {
        if (!isClientEnvironment()) {
            throw new IllegalStateException("Запись с микрофона доступна только на клиенте.");
        }

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
        }, "AiLib-Mic-Recorder");
        recordingThread.start();

        try {
            Thread.sleep(durationSeconds * 1000L);
        } finally {
            line.stop();
            line.close();
            recordingThread.join(2000);
        }
    }
}