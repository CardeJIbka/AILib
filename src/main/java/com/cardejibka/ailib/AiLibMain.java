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
import java.util.concurrent.atomic.AtomicBoolean;

public class AiLibMain implements ModInitializer {
    public static final String MOD_ID = "ailib";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile boolean ready = false;
    private static final AtomicBoolean preparing = new AtomicBoolean(false);

    @Override
    public void onInitialize() {
        LOGGER.info("Инициализация AiLib Common...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        prepareEverythingAsync();
    }

    public static boolean isReady() {
        return ready;
    }

    /** Можно вызывать повторно (например, командой) — если уже готово или уже качается, ничего не сломает. */
    public static CompletableFuture<Void> prepareEverythingAsync() {
        if (!preparing.compareAndSet(false, true)) {
            LOGGER.info("Подготовка AiLib уже выполняется.");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture
                .supplyAsync(() -> NativeDownloader.prepareAndLoadAllNatives(), AiLibExecutors.IO_EXECUTOR)
                .thenCombine(
                        CompletableFuture.supplyAsync(ModelDownloader::prepareModels, AiLibExecutors.IO_EXECUTOR),
                        (nativesOk, modelsOk) -> nativesOk && modelsOk)
                .thenAccept(allOk -> {
                    ready = allOk;
                    if (allOk) {
                        LOGGER.info("AiLib готов к работе: нативы и модели загружены.");
                    } else {
                        LOGGER.error("AiLib не смог подготовить все компоненты. Команды llm/tts/stt могут не работать.");
                    }
                })
                .whenComplete((v, err) -> {
                    if (err != null) {
                        LOGGER.error("Ошибка подготовки AiLib", err);
                    }
                    preparing.set(false);
                });
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

                                    if (!requireReady(source)) return 0;

                                    source.sendSuccess(() -> Component.literal("§7[LLM] Генерация ответа..."), false);

                                    CompletableFuture.runAsync(() -> {
                                        String response = LlamaEngine.generate(prompt);
                                        source.sendSuccess(() -> Component.literal("§a[LLM Ответ]: §f" + response), false);
                                    }, AiLibExecutors.IO_EXECUTOR);

                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("tts")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String text = StringArgumentType.getString(context, "text");

                                    if (!requireReady(source)) return 0;

                                    source.sendSuccess(() -> Component.literal("§7[TTS] Синтез речи..."), false);

                                    CompletableFuture.runAsync(() -> {
                                        byte[] wavData = PiperEngine.synthesize(text);
                                        if (wavData.length > 0) {
                                            Path outputPath = getTempDir().resolve("output.wav");
                                            boolean played = AudioHelper.playAndSave(wavData, outputPath);
                                            String suffix = played ? "и воспроизводится." : "(воспроизведение недоступно на этой стороне).";
                                            source.sendSuccess(() -> Component.literal(
                                                    "§a[TTS]: §fСинтезировано " + wavData.length +
                                                            " байт, сохранено в " + outputPath + " " + suffix), false);
                                        } else {
                                            source.sendFailure(Component.literal("§c[TTS Error]: Не удалось сгенерировать аудио."));
                                        }
                                    }, AiLibExecutors.IO_EXECUTOR);

                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("stt")
                        .then(Commands.argument("filePath", StringArgumentType.greedyString())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String filePathStr = StringArgumentType.getString(context, "filePath");

                                    if (!requireReady(source)) return 0;

                                    source.sendSuccess(() -> Component.literal("§7[STT] Распознавание файла..."), false);

                                    CompletableFuture.runAsync(() -> {
                                        Path wavPath = Path.of(filePathStr);
                                        String text = WhisperEngine.transcribe(wavPath);
                                        source.sendSuccess(() -> Component.literal("§a[STT Текст]: §f" + text), false);
                                    }, AiLibExecutors.IO_EXECUTOR);

                                    return 1;
                                })
                        )
                );

        // record / ask используют микрофон и воспроизведение — это чисто клиентские
        // операции. Раньше их можно было вызвать и на выделенном сервере, получив
        // невнятную ошибку javax.sound.sampled в консоли вместо понятного отказа.
        if (isClient) {
            ailibRoot
                    .then(Commands.literal("record")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        int seconds = IntegerArgumentType.getInteger(context, "seconds");

                                        if (!requireReady(source)) return 0;

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
                                        }, AiLibExecutors.IO_EXECUTOR);

                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("ask")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        int seconds = IntegerArgumentType.getInteger(context, "seconds");

                                        if (!requireReady(source)) return 0;

                                        source.sendSuccess(() -> Component.literal(
                                                "§7[Ask] Запись " + seconds + " сек..."), false);

                                        CompletableFuture.runAsync(() -> {
                                            try {
                                                Path micPath = getTempDir().resolve("mic_input.wav");
                                                AudioHelper.recordMic(micPath, seconds);

                                                source.sendSuccess(() -> Component.literal("§7[Ask] Распознавание..."), false);
                                                String recognizedText = WhisperEngine.transcribe(micPath);
                                                source.sendSuccess(() -> Component.literal("§7[Вы сказали]: §f" + recognizedText), false);

                                                source.sendSuccess(() -> Component.literal("§7[Ask] Генерация ответа..."), false);
                                                String response = LlamaEngine.generate(recognizedText);
                                                source.sendSuccess(() -> Component.literal("§a[LLM Ответ]: §f" + response), false);

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
                                        }, AiLibExecutors.IO_EXECUTOR);

                                        return 1;
                                    })
                            )
                    );
        }

        dispatcher.register(ailibRoot);
    }

    private boolean requireReady(CommandSourceStack source) {
        if (!ready) {
            source.sendFailure(Component.literal(
                    "§c[AiLib]: Модели/бинарники ещё не готовы (идёт первичная загрузка или она не удалась). Смотрите логи."));
            return false;
        }
        return true;
    }
}