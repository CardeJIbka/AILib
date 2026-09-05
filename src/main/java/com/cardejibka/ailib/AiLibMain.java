package com.cardejibka.ailib;

import com.cardejibka.ailib.downloader.ModelDownloader;
import com.cardejibka.ailib.downloader.NativeDownloader;
import com.cardejibka.ailib.engine.LlamaEngine;
import com.cardejibka.ailib.engine.PiperEngine;
import com.cardejibka.ailib.engine.WhisperEngine;
import com.cardejibka.ailib.utils.AudioHelper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

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
        LOGGER.info("Инициализация AiLib Common...");

        // Регистрация игровых команд
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    /**
     * Папка для временных аудиофайлов (запись с микрофона / результат TTS).
     * Создаётся при необходимости.
     */
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
        dispatcher.register(Commands.literal("ailib")
                // Команда LLM
                .then(Commands.literal("llm")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String prompt = StringArgumentType.getString(context, "prompt");

                                    source.sendSuccess(() -> Component.literal("§7[LLM] Генерация ответа..."), false);

                                    // Асинхронное выполнение, чтобы не вешать клиент/сервер
                                    CompletableFuture.runAsync(() -> {
                                        String response = LlamaEngine.generate(prompt);
                                        source.sendSuccess(() -> Component.literal("§a[LLM Ответ]: §f" + response), false);
                                    });

                                    return 1;
                                })
                        )
                )
                // Команда TTS: синтезирует речь, сохраняет в output.wav (перезаписывая) и воспроизводит в игре
                .then(Commands.literal("tts")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String text = StringArgumentType.getString(context, "text");

                                    source.sendSuccess(() -> Component.literal("§7[TTS] Синтез речи..."), false);

                                    CompletableFuture.runAsync(() -> {
                                        byte[] wavData = PiperEngine.synthesize(text);
                                        if (wavData.length > 0) {
                                            Path outputPath = getTempDir().resolve("output.wav");
                                            AudioHelper.playAndSave(wavData, outputPath);
                                            source.sendSuccess(() -> Component.literal(
                                                    "§a[TTS]: §fСинтезировано " + wavData.length +
                                                            " байт, сохранено в " + outputPath + " и воспроизводится."), false);
                                        } else {
                                            source.sendFailure(Component.literal("§c[TTS Error]: Не удалось сгенерировать аудио."));
                                        }
                                    });

                                    return 1;
                                })
                        )
                )
                // Команда STT (пример передачи пути к wav)
                .then(Commands.literal("stt")
                        .then(Commands.argument("filePath", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String filePathStr = StringArgumentType.getString(context, "filePath");

                                    source.sendSuccess(() -> Component.literal("§7[STT] Распознавание файла..."), false);

                                    CompletableFuture.runAsync(() -> {
                                        Path wavPath = Path.of(filePathStr);
                                        String text = WhisperEngine.transcribe(wavPath);
                                        source.sendSuccess(() -> Component.literal("§a[STT Текст]: §f" + text), false);
                                    });

                                    return 1;
                                })
                        )
                )
                // Команда записи с микрофона: пишет N секунд, распознаёт и выводит текст в чат
                .then(Commands.literal("record")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    int seconds = IntegerArgumentType.getInteger(context, "seconds");

                                    source.sendSuccess(() -> Component.literal(
                                            "§7[Микрофон] Запись " + seconds + " сек..."), false);

                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            Path micPath = getTempDir().resolve("mic_input.wav");
                                            AudioHelper.recordMic(micPath, seconds);

                                            source.sendSuccess(() -> Component.literal("§7[Микрофон] Распознавание..."), false);
                                            String text = WhisperEngine.transcribe(micPath);
                                            source.sendSuccess(() -> Component.literal("§a[Вы сказали]: §f" + text), false);
                                        } catch (Exception e) {
                                            LOGGER.error("[AiLib] Ошибка записи с микрофона", e);
                                            source.sendFailure(Component.literal("§c[Ошибка микрофона]: " + e.getMessage()));
                                        }
                                    });

                                    return 1;
                                })
                        )
                )
                // Гибридная команда: запись голоса -> распознавание -> LLM -> озвучка ответа
                .then(Commands.literal("ask")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    int seconds = IntegerArgumentType.getInteger(context, "seconds");

                                    source.sendSuccess(() -> Component.literal(
                                            "§7[Ask] Запись " + seconds + " сек..."), false);

                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            // 1. Запись с микрофона
                                            Path micPath = getTempDir().resolve("mic_input.wav");
                                            AudioHelper.recordMic(micPath, seconds);

                                            // 2. Распознавание речи
                                            source.sendSuccess(() -> Component.literal("§7[Ask] Распознавание..."), false);
                                            String recognizedText = WhisperEngine.transcribe(micPath);
                                            source.sendSuccess(() -> Component.literal("§7[Вы сказали]: §f" + recognizedText), false);

                                            // 3. Запрос к LLM
                                            source.sendSuccess(() -> Component.literal("§7[Ask] Генерация ответа..."), false);
                                            String response = LlamaEngine.generate(recognizedText);
                                            source.sendSuccess(() -> Component.literal("§a[LLM Ответ]: §f" + response), false);

                                            // 4. Озвучка ответа
                                            byte[] wavData = PiperEngine.synthesize(response);
                                            if (wavData.length > 0) {
                                                Path outputPath = getTempDir().resolve("output.wav");
                                                AudioHelper.playAndSave(wavData, outputPath);
                                            } else {
                                                source.sendFailure(Component.literal("§c[TTS Error]: Не удалось озвучить ответ."));
                                            }
                                        } catch (Exception e) {
                                            LOGGER.error("[AiLib] Ошибка выполнения команды ask", e);
                                            source.sendFailure(Component.literal("§c[Ask Error]: " + e.getMessage()));
                                        }
                                    });

                                    return 1;
                                })
                        )
                )
        );
    }
}