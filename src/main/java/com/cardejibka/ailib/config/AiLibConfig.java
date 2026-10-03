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
 * Settings stored in config/ailib.json. A missing file is created with defaults.
 * An existing but broken file is NOT overwritten (so manual edits are never lost);
 * defaults are used for the current run instead.
 */
public final class AiLibConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiLib-Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile AiLibConfig instance;

    // --- General ---
    /** false = download nothing at startup; everything is fetched lazily on the first API call. */
    public boolean bootstrapDefaults = true;
    /** Permission level required for /ailib commands (default 2 = operators). */
    public int commandPermissionLevel = 2;

    // --- LLM ---
    public String llmSystemPrompt = "Ты полезный ассистент. Отвечай кратко и чётко.";
    public int llmMaxTokens = 128;
    public int llmContextSize = 2048;
    public double llmTemperature = 0.6;
    public int llmTimeoutSeconds = 35;
    /** Extra llama-cli arguments, e.g. ["-no-cnv"] for builds that default to conversation mode. */
    public List<String> llmExtraArgs = List.of();

    // --- TTS ---
    public int ttsTimeoutSeconds = 15;

    // --- STT ---
    public String sttLanguage = "auto";
    public int sttThreads = 2;
    public int sttTimeoutSeconds = 30;

    // --- Downloads ---
    public int maxParallelDownloads = 3;
    /**
     * Domains models registered by third-party mods may be downloaded from. Checked against the host
     * of the original URL (exact match or subdomain). Redirects to CDNs are not checked.
     * The built-in natives are not subject to this check: their URLs are fixed in the library.
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
                LOGGER.error("Could not read {} ({}). The file was NOT overwritten: fix it by hand; "
                        + "defaults are used for now.", path, e.getMessage());
            }
            return new AiLibConfig(); // the file exists but is unusable: leave it alone
        }

        AiLibConfig defaults = new AiLibConfig();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(defaults));
        } catch (Exception e) {
            LOGGER.error("Could not write the default config to {}: {}", path, e.getMessage());
        }
        return defaults;
    }

    /** Drops the cache and re-reads the file from disk. */
    public static synchronized void reload() {
        instance = load();
    }

    /** Repairs nulls and nonsensical values coming from a hand-edited JSON file. */
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
