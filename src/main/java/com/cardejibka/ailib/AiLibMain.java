package com.cardejibka.ailib;

import com.cardejibka.ailib.api.AiLib;
import com.cardejibka.ailib.api.AiLibException;
import com.cardejibka.ailib.config.AiLibConfig;
import com.cardejibka.ailib.utils.AudioHelper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.RejectedExecutionException;

public class AiLibMain implements ModInitializer {
    public static final String MOD_ID = "ailib";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Инициализация AiLib...");
        // Конфиг первым делом — до того, как executor'ы/движки запросят значения.
        AiLibConfig.get();
        AiLib.bootstrapDefaults();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private static Path getTempDir() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("ailib_temp");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOGGER.error("[AiLib] Не удалось создать временную папку {}", dir, e);
        }
        return dir;
    }

    /** Уровень прав берётся из config/ailib.json (commandPermissionLevel, по умолчанию 2 = оператор). */
    private static boolean hasAccess(CommandSourceStack source) {
        return source.hasPermission(AiLibConfig.get().commandPermissionLevel);
    }

    /** Отправка сообщения из фонового потока — через главный поток сервера. */
    private static void reply(CommandSourceStack source, String text, boolean failure) {
        Runnable send = () -> {
            if (failure) source.sendFailure(Component.literal(text));
            else source.sendSuccess(() -> Component.literal(text), false);
        };
        MinecraftServer server = source.getServer();
        if (server != null) server.execute(send);
        else send.run();
    }

    private static void runAsync(CommandSourceStack source, String tag, Task task) {
        try {
            AiLibExecutors.CALL_EXECUTOR.execute(() -> {
                try {
                    task.run();
                } catch (AiLibException e) {
                    reply(source, "§c[" + tag + "]: " + describe(e), true);
                } catch (Exception e) {
                    LOGGER.error("[AiLib] Ошибка в команде {}", tag, e);
                    reply(source, "§c[" + tag + "]: " + e.getMessage(), true);
                }
            });
        } catch (RejectedExecutionException e) {
            reply(source, "§c[" + tag + "]: слишком много запросов, попробуйте позже", true);
        }
    }

    /** Файлы для STT разрешены только внутри папки игры — иначе оператор читает любой файл сервера. */
    private static Path resolveSttPath(String raw) {
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path resolved = gameDir.resolve(raw).normalize();
        if (!resolved.startsWith(gameDir)) {
            throw new IllegalArgumentException("Путь должен находиться внутри папки игры");
        }
        return resolved;
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        boolean isClient = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

        var ailibRoot = Commands.literal("ailib")
                .requires(AiLibMain::hasAccess)
                .then(Commands.literal("llm")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String prompt = StringArgumentType.getString(context, "prompt");
                                    reply(source, "§7[LLM] Генерация ответа...", false);
                                    runAsync(source, "LLM", () ->
                                            reply(source, "§a[LLM Ответ]: §f" + AiLib.generate(prompt), false));
                                    return 1;
                                })))
                .then(Commands.literal("tts")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String text = StringArgumentType.getString(context, "text");
                                    reply(source, "§7[TTS] Синтез речи...", false);
                                    runAsync(source, "TTS", () -> {
                                        byte[] wavData = AiLib.synthesize(text);
                                        Path outputPath = getTempDir().resolve("output.wav");
                                        boolean played = AudioHelper.playAndSave(wavData, outputPath);
                                        String suffix = played ? "и воспроизводится." : "(воспроизведение недоступно на этой стороне).";
                                        reply(source, "§a[TTS]: §fСинтезировано " + wavData.length
                                                + " байт, сохранено в " + outputPath + " " + suffix, false);
                                    });
                                    return 1;
                                })))
                .then(Commands.literal("stt")
                        .then(Commands.argument("filePath", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String filePathStr = StringArgumentType.getString(context, "filePath");
                                    reply(source, "§7[STT] Распознавание файла...", false);
                                    runAsync(source, "STT", () -> {
                                        String text = AiLib.transcribe(resolveSttPath(filePathStr));
                                        reply(source, "§a[STT Текст]: §f" + text, false);
                                    });
                                    return 1;
                                })))
                .then(Commands.literal("models")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            var models = AiLib.models();
                            if (models.isEmpty()) {
                                reply(source, "§7[Models] Нет зарегистрированных моделей", false);
                            }
                            for (var m : models) {
                                String extra = m.state() == com.cardejibka.ailib.api.ModelHandle.State.FAILED
                                        ? " (" + m.failureReason() + ")"
                                        : (m.progress() >= 0 && !m.isReady() ? " " + Math.round(m.progress() * 100) + "%" : "");
                                reply(source, "§7[Models] §f" + m.spec().id() + " — " + m.state() + extra, false);
                            }
                            return 1;
                        }));

        // record/ask используют микрофон и воспроизведение — чисто клиентские операции.
        if (isClient) {
            ailibRoot
                    .then(Commands.literal("record")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        int seconds = IntegerArgumentType.getInteger(context, "seconds");
                                        reply(source, "§7[Микрофон] Запись " + seconds + " сек...", false);
                                        runAsync(source, "Микрофон", () -> {
                                            Path micPath = getTempDir().resolve("mic_input.wav");
                                            AudioHelper.recordMic(micPath, seconds);
                                            reply(source, "§7[Микрофон] Распознавание...", false);
                                            reply(source, "§a[Вы сказали]: §f" + AiLib.transcribe(micPath), false);
                                        });
                                        return 1;
                                    })))
                    .then(Commands.literal("ask")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        int seconds = IntegerArgumentType.getInteger(context, "seconds");
                                        reply(source, "§7[Ask] Запись " + seconds + " сек...", false);
                                        runAsync(source, "Ask", () -> {
                                            Path micPath = getTempDir().resolve("mic_input.wav");
                                            AudioHelper.recordMic(micPath, seconds);

                                            reply(source, "§7[Ask] Распознавание...", false);
                                            String recognized = AiLib.transcribe(micPath);
                                            reply(source, "§7[Вы сказали]: §f" + recognized, false);

                                            reply(source, "§7[Ask] Генерация ответа...", false);
                                            String response = AiLib.generate(recognized);
                                            reply(source, "§a[LLM Ответ]: §f" + response, false);

                                            byte[] wavData = AiLib.synthesize(response);
                                            AudioHelper.playAndSave(wavData, getTempDir().resolve("output.wav"));
                                        });
                                        return 1;
                                    })));
        }

        dispatcher.register(ailibRoot);
    }

    private static String describe(AiLibException e) {
        if (e.getReason() == AiLibException.Reason.NOT_READY && e.getProgressPercent() >= 0) {
            return e.getMessage() + " (" + e.getProgressPercent() + "%)";
        }
        return e.getMessage();
    }
}
