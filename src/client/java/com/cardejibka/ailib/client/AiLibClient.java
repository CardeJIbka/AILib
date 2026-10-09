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
        ProgressBus.subscribe(new DownloadTracker());

        HudRenderCallback.EVENT.register(LoadingOverlay::render);
    }
}
