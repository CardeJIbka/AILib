package com.cardejibka.ailib.client;

import com.cardejibka.ailib.downloader.ProgressBus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * The only reason for a separate client entrypoint: the HUD (download cards) is client-side
 * rendering and cannot live in common code. Everything else (downloads, engines) is common and
 * works the same on a dedicated server, just without the HUD on top.
 */
public class AiLibClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // DownloadTracker knows nothing about AiLibBootstrap: it is just a ProgressBus subscriber,
        // so another mod can add or replace it with its own HUD just as easily.
        ProgressBus.subscribe(new DownloadTracker());

        // The HudRenderCallback signature here is (GuiGraphics, DeltaTracker), which is what 1.21.2+ uses.
        // On 1.21/1.21.1 the second parameter is a float partialTick and LoadingOverlay.render must change back.
        HudRenderCallback.EVENT.register(LoadingOverlay::render);
    }
}
