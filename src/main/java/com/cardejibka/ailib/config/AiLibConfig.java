package com.cardejibka.ailib.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Настройки, которые раньше были захардкожены прямо в движках (системный промпт,
 * число потоков, таймауты) или нигде не существовали вообще (allow-list доменов
 * для скачивания моделей по чужой ссылке). Лежит в config/ailib.json — если файла
 * нет, создаётся с дефолтами при первом обращении.
 * <p>
 * Gson тут не новая зависимость — он уже транзитивно тянется вместе с Minecraft/Fabric,
 * так что подключать ничего дополнительно не нужно.
 */
public final class AiLibConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile AiLibConfig instance;

    // --- LLM ---
    public String llmSystemPrompt = "Ты полезный ассистент. Отвечай кратко и чётко.";
    public int llmMaxTokens = 128;
    public int llmContextSize = 2048;
    public double llmTemperature = 0.6;
    public int llmTimeoutSeconds = 35;

    // --- TTS ---
    public int ttsTimeoutSeconds = 15;

    // --- STT ---
    public String sttLanguage = "auto";
    public int sttThreads = 2;
    public int sttTimeoutSeconds = 30;

    // --- Скачивание ---
    public int maxParallelDownloads = 3;
    /**
     * Домены, с которых разрешено скачивать МОДЕЛИ, зарегистрированные сторонними
     * модами через AiLib.registerModel(...). Проверяется по хосту URL (точное
     * совпадение или поддомен). Встроенные нативы (llama.cpp/whisper.cpp/piper)
     * этой проверке не подлежат — их ссылки фиксированы в самой библиотеке.
     */
    public List<String> allowedModelDownloadDomains = List.of(
            "huggingface.co",
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com"
    );

    public static AiLibConfig get() {
        AiLibConfig local = instance;
        if (local == null) {
            synchronized (AiLibConfig.class) {
                local = instance;
                if (local == null) {
                    instance = local = load();
                }
            }
        }
        return local;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("ailib.json");
    }

    private static AiLibConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                AiLibConfig loaded = GSON.fromJson(json, AiLibConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception e) {
                LOGGER.error("Не удалось прочитать {}, использую значения по умолчанию: {}", path, e.getMessage());
            }
        }

        AiLibConfig defaults = new AiLibConfig();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(defaults));
        } catch (Exception e) {
            LOGGER.error("Не удалось записать конфиг по умолчанию в {}: {}", path, e.getMessage());
        }
        return defaults;
    }

    /** Сбросить кэш и перечитать файл с диска — полезно, если добавишь команду /ailib reload. */
    public static synchronized void reload() {
        instance = load();
    }

    public boolean isDomainAllowed(String host) {
        if (host == null) return false;
        String lowerHost = host.toLowerCase(java.util.Locale.ROOT);
        for (String domain : allowedModelDownloadDomains) {
            String lowerDomain = domain.toLowerCase(java.util.Locale.ROOT);
            if (lowerHost.equals(lowerDomain) || lowerHost.endsWith("." + lowerDomain)) {
                return true;
            }
        }
        return false;
    }
}