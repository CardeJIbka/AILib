package com.cardejibka.ailib.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Настройки в config/ailib.json. Если файла нет — создаётся с дефолтами.
 * Если файл есть, но битый — он НЕ перезаписывается (правки пользователя не теряются),
 * на время работы используются дефолты.
 */
public final class AiLibConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile AiLibConfig instance;

    // --- Общее ---
    /** false = ничего не качать при старте, всё лениво при первом вызове API. */
    public boolean bootstrapDefaults = true;
    /** Уровень прав для команд /ailib (по умолчанию 2 = операторы). */
    public int commandPermissionLevel = 2;

    // --- LLM ---
    public String llmSystemPrompt = "Ты полезный ассистент. Отвечай кратко и чётко.";
    public int llmMaxTokens = 128;
    public int llmContextSize = 2048;
    public double llmTemperature = 0.6;
    public int llmTimeoutSeconds = 35;
    /** Доп. аргументы llama-cli, например ["-no-cnv"] для сборок, где по умолчанию включён диалоговый режим. */
    public List<String> llmExtraArgs = List.of();

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
     * модами. Проверяется по хосту исходного URL (точное совпадение или поддомен).
     * Редиректы на CDN этой проверке не подлежат. Нативы — ссылки фиксированы в библиотеке.
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
                AiLibConfig loaded = GSON.fromJson(Files.readString(path), AiLibConfig.class);
                if (loaded != null) {
                    loaded.sanitize();
                    return loaded;
                }
            } catch (Exception e) {
                LOGGER.error("Не удалось прочитать {} ({}). Файл НЕ перезаписан — исправь его вручную; "
                        + "пока используются значения по умолчанию.", path, e.getMessage());
            }
            return new AiLibConfig(); // файл существует, но непригоден: не трогаем его
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

    /** Сбросить кэш и перечитать файл с диска. */
    public static synchronized void reload() {
        instance = load();
    }

    /** Чинит null и неадекватные значения, пришедшие из ручной правки JSON. */
    public void sanitize() {
        AiLibConfig d = new AiLibConfig();
        if (llmSystemPrompt == null) llmSystemPrompt = d.llmSystemPrompt;
        if (sttLanguage == null || sttLanguage.isBlank()) sttLanguage = d.sttLanguage;
        if (allowedModelDownloadDomains == null) allowedModelDownloadDomains = d.allowedModelDownloadDomains;
        if (llmExtraArgs == null) llmExtraArgs = List.of();
        llmMaxTokens = Math.max(1, llmMaxTokens);
        llmContextSize = Math.max(128, llmContextSize);
        if (Double.isNaN(llmTemperature) || llmTemperature < 0) llmTemperature = d.llmTemperature;
        llmTimeoutSeconds = Math.max(1, llmTimeoutSeconds);
        ttsTimeoutSeconds = Math.max(1, ttsTimeoutSeconds);
        sttThreads = Math.max(1, sttThreads);
        sttTimeoutSeconds = Math.max(1, sttTimeoutSeconds);
        maxParallelDownloads = Math.max(1, maxParallelDownloads);
        commandPermissionLevel = Math.max(0, Math.min(4, commandPermissionLevel));
    }

    public boolean isDomainAllowed(String host) {
        if (host == null || allowedModelDownloadDomains == null) return false;
        String lowerHost = host.toLowerCase(Locale.ROOT);
        for (String domain : allowedModelDownloadDomains) {
            if (domain == null) continue;
            String lowerDomain = domain.toLowerCase(Locale.ROOT);
            if (lowerHost.equals(lowerDomain) || lowerHost.endsWith("." + lowerDomain)) {
                return true;
            }
        }
        return false;
    }
}
