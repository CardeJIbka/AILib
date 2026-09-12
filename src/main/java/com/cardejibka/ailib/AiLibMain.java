package com.cardejibka.ailib;

import com.cardejibka.ailib.api.AiLib;
import com.cardejibka.ailib.api.AiLibException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class AiLibMain implements ModInitializer {
    public static final String MOD_ID = "ailib";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Инициализация AiLib...");

        // Запускает параллельную фоновую загрузку нативов и моделей по умолчанию.
        // Не блокирует — метод возвращается сразу, игра грузится дальше как обычно.
        // Прогресс каждого артефакта летит в ProgressBus (см. downloader-пакет),
        // откуда его на клиенте подхватывает HUD.
        AiLib.bootstrapDefaults();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private Path getTempDir() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("ailib_temp");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOGGER.error("[AiLib] Не удалось создать временную папку {}", dir, e);
        }
        return dir;
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        boolean isClient = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

        var ailibRoot = Commands.literal("ailib")
                .then(Commands.literal("llm")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String prompt = StringArgumentType.getString(context, "prompt");

                                    source.sendSuccess(() -> Component.literal("§7[LLM] Генерация ответа..."), false);
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            String response = AiLib.generate(prompt);
                                            source.sendSuccess(() -> Component.literal("§a[LLM Ответ]: §f" + response), false);
                                        } catch (AiLibException e) {
                                            source.sendFailure(Component.literal("§c[LLM]: " + describe(e)));
                                        }
                                    }, AiLibExecutors.PROCESS_IO_EXECUTOR);

                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("tts")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String text = StringArgumentType.getString(context, "text");

                                    source.sendSuccess(() -> Component.literal("§7[TTS] Синтез речи..."), false);
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            byte[] wavData = AiLib.synthesize(text);
                                            Path outputPath = getTempDir().resolve("output.wav");
                                            boolean played = AudioHelper.playAndSave(wavData, outputPath);
                                            String suffix = played ? "и воспроизводится." : "(воспроизведение недоступно на этой стороне).";
                                            source.sendSuccess(() -> Component.literal(
                                                    "§a[TTS]: §fСинтезировано " + wavData.length + " байт, сохранено в " + outputPath + " " + suffix), false);
                                        } catch (AiLibException e) {
                                            source.sendFailure(Component.literal("§c[TTS]: " + describe(e)));
                                        }
                                    }, AiLibExecutors.PROCESS_IO_EXECUTOR);

                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("stt")
                        .then(Commands.argument("filePath", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String filePathStr = StringArgumentType.getString(context, "filePath");

                                    source.sendSuccess(() -> Component.literal("§7[STT] Распознавание файла..."), false);
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            String text = AiLib.transcribe(Path.of(filePathStr));
                                            source.sendSuccess(() -> Component.literal("§a[STT Текст]: §f" + text), false);
                                        } catch (AiLibException e) {
                                            source.sendFailure(Component.literal("§c[STT]: " + describe(e)));
                                        }
                                    }, AiLibExecutors.PROCESS_IO_EXECUTOR);

                                    return 1;
                                })
                        )
                );

        // record/ask используют микрофон и воспроизведение — чисто клиентские
        // операции, поэтому регистрируются только там.
        if (isClient) {
            ailibRoot
                    .then(Commands.literal("record")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        int seconds = IntegerArgumentType.getInteger(context, "seconds");

                                        source.sendSuccess(() -> Component.literal("§7[Микрофон] Запись " + seconds + " сек..."), false);
                                        CompletableFuture.runAsync(() -> {
                                            try {
                                                Path micPath = getTempDir().resolve("mic_input.wav");
                                                AudioHelper.recordMic(micPath, seconds);

                                                source.sendSuccess(() -> Component.literal("§7[Микрофон] Распознавание..."), false);
                                                String text = AiLib.transcribe(micPath);
                                                source.sendSuccess(() -> Component.literal("§a[Вы сказали]: §f" + text), false);
                                            } catch (AiLibException e) {
                                                source.sendFailure(Component.literal("§c[Микрофон]: " + describe(e)));
                                            } catch (Exception e) {
                                                LOGGER.error("[AiLib] Ошибка записи с микрофона", e);
                                                source.sendFailure(Component.literal("§c[Ошибка микрофона]: " + e.getMessage()));
                                            }
                                        }, AiLibExecutors.PROCESS_IO_EXECUTOR);

                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("ask")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        int seconds = IntegerArgumentType.getInteger(context, "seconds");

                                        source.sendSuccess(() -> Component.literal("§7[Ask] Запись " + seconds + " сек..."), false);
                                        CompletableFuture.runAsync(() -> {
                                            try {
                                                Path micPath = getTempDir().resolve("mic_input.wav");
                                                AudioHelper.recordMic(micPath, seconds);

                                                source.sendSuccess(() -> Component.literal("§7[Ask] Распознавание..."), false);
                                                String recognizedText = AiLib.transcribe(micPath);
                                                source.sendSuccess(() -> Component.literal("§7[Вы сказали]: §f" + recognizedText), false);

                                                source.sendSuccess(() -> Component.literal("§7[Ask] Генерация ответа..."), false);
                                                String response = AiLib.generate(recognizedText);
                                                source.sendSuccess(() -> Component.literal("§a[LLM Ответ]: §f" + response), false);

                                                byte[] wavData = AiLib.synthesize(response);
                                                Path outputPath = getTempDir().resolve("output.wav");
                                                AudioHelper.playAndSave(wavData, outputPath);
                                            } catch (AiLibException e) {
                                                source.sendFailure(Component.literal("§c[Ask]: " + describe(e)));
                                            } catch (Exception e) {
                                                LOGGER.error("[AiLib] Ошибка выполнения команды ask", e);
                                                source.sendFailure(Component.literal("§c[Ask Error]: " + e.getMessage()));
                                            }
                                        }, AiLibExecutors.PROCESS_IO_EXECUTOR);

                                        return 1;
                                    })
                            )
                    );
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