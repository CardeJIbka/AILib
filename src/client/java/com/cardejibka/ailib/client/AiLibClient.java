package com.cardejibka.ailib.client;

import com.cardejibka.ailib.downloader.ProgressBus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * Единственная причина существования отдельного client-entrypoint в текущей
 * архитектуре: HUD (спиннер загрузки) — это клиентский рендер, который не может
 * жить в common-коде. Вся остальная логика (скачивание, движки) — common и
 * работает на выделенном сервере точно так же, просто без HUD поверх.
 */
public class AiLibClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // DownloadTracker сам по себе не знает о AiLibBootstrap — он просто подписчик
        // на ProgressBus, поэтому его можно так же легко заменить/дополнить другим
        // мод-потребителем, который захочет свой собственный HUD.
        ProgressBus.subscribe(new DownloadTracker());

        // Сигнатура HudRenderCallback здесь — (GuiGraphics, DeltaTracker), актуальная
        // для 1.21.2+. Если когда-нибудь откатишься на 1.21/1.21.1, там второй параметр —
        // float partialTick, и сигнатуру LoadingOverlay.render нужно будет вернуть обратно.
        HudRenderCallback.EVENT.register(LoadingOverlay::render);
    }
}