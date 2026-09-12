package com.cardejibka.ailib.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

public class LoadingOverlay {

    private static final int CARD_WIDTH = 160;
    private static final int CARD_HEIGHT = 36;
    private static final int GAP = 6;
    private static final int MARGIN = 10;
    private static final int MAX_VISIBLE = 4;

    // Начиная с 1.21.2 HudRenderCallback передаёт DeltaTracker вместо float partialTick.
    // Сам параметр здесь не используется (спиннер анимируется по System.currentTimeMillis(),
    // не по partial tick), но сигнатура должна совпадать с HudRenderCallback.EVENT, иначе
    // метод-ссылка LoadingOverlay::render не скомпилируется.
    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        List<DownloadTracker.TaskState> tasks = DownloadTracker.getActiveTasks();
        if (tasks.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int visibleCount = Math.min(tasks.size(), MAX_VISIBLE);
        int x = screenWidth - CARD_WIDTH - MARGIN;
        int y = screenHeight - MARGIN - CARD_HEIGHT;

        for (int i = 0; i < visibleCount; i++) {
            renderCard(guiGraphics, mc, tasks.get(i), x, y);
            y -= (CARD_HEIGHT + GAP);
        }

        int extra = tasks.size() - visibleCount;
        if (extra > 0) {
            String moreText = "+" + extra + " ещё загружается...";
            guiGraphics.drawString(mc.font, moreText, x, y + CARD_HEIGHT - 4, 0xFFAAAAAA, true);
        }
    }

    private static void renderCard(GuiGraphics guiGraphics, Minecraft mc, DownloadTracker.TaskState task, int x, int y) {
        // 1. Полупрозрачная плашка-фон
        guiGraphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, 0x80000000);

        // 2. Анимированный спиннер (вращение по времени)
        long time = System.currentTimeMillis();
        float angle = (time % 1000) / 1000.0f * 360.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x + 18, y + 18, 0);
        guiGraphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(angle));
        guiGraphics.fill(-6, -6, 6, 6, 0xFFFFAA00);
        guiGraphics.pose().popPose();

        // 3. Текст статуса (обрезаем, чтобы не вылезал за карточку)
        String text = mc.font.plainSubstrByWidth(task.label(), CARD_WIDTH - 40);
        guiGraphics.drawString(mc.font, text, x + 34, y + 6, 0xFFFFFFFF, true);

        // 4. Прогресс-бар (или "неизвестно" при total == -1, т.е. сервер не прислал размер)
        int barX = x + 34;
        int barY = y + 20;
        int barWidth = CARD_WIDTH - 44;
        int barHeight = 6;

        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF555555);

        float progress = task.progress();
        if (progress >= 0f) {
            int filledWidth = (int) (barWidth * progress);
            guiGraphics.fill(barX, barY, barX + filledWidth, barY + barHeight, 0xFF55FF55);
        } else {
            // Неизвестный размер — бегущий индикатор вместо статичного бара.
            long time2 = System.currentTimeMillis();
            int indicatorWidth = Math.max(12, barWidth / 4);
            int offset = (int) ((time2 / 4) % (barWidth + indicatorWidth)) - indicatorWidth;
            int startX = barX + Math.max(0, offset);
            int endX = barX + Math.min(barWidth, offset + indicatorWidth);
            if (endX > startX) {
                guiGraphics.fill(startX, barY, endX, barY + barHeight, 0xFF55AAFF);
            }
        }
    }
}