package com.cardejibka.ailib.client;

import com.cardejibka.ailib.downloader.ModelDownloader;
import com.cardejibka.ailib.downloader.NativeDownloader;
import net.fabricmc.api.ClientModInitializer;

public class AiLibClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        NativeDownloader.prepareAndLoadAllNatives();
        ModelDownloader.prepareModels();
    }
}