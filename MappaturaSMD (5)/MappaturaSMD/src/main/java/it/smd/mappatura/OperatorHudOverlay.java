package it.smd.mappatura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

final class OperatorHudOverlay {

    private OperatorHudOverlay() {}

    public static void render(DrawContext context, MappingController controller) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || context == null) return;
        if (client.options != null && client.options.hudHidden) return;

        String operator = client.getSession() != null ? client.getSession().getUsername() : "Operatore";
        int mappedCount = PlotCacheManager.getMappedCount();
        int pendingCount = controller != null ? controller.getPendingSubmitCount() : 0;
        int pendingRequests = controller != null ? controller.getPendingPlotRequestCount() : 0;
        boolean awaitingPlotInfo = controller != null && controller.isAwaitingPlotInfo();
        boolean running = controller != null && controller.isRunning();

        MutableText line1 = Text.literal("Operatore: ").formatted(Formatting.GRAY)
                .append(Text.literal(operator).formatted(Formatting.WHITE))
                .append(Text.literal(" | Stato: ").formatted(Formatting.GRAY))
                .append(Text.literal(running ? "ON" : "OFF").formatted(running ? Formatting.GREEN : Formatting.RED));

        MutableText line2 = Text.literal("Plot mappati: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(mappedCount)).formatted(Formatting.AQUA))
                .append(Text.literal(" | In coda: ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(pendingCount)).formatted(Formatting.YELLOW));

        MutableText line3 = Text.literal("Richieste plot: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(pendingRequests)).formatted(Formatting.YELLOW))
                .append(Text.literal(" | Comando: ").formatted(Formatting.GRAY))
                .append(Text.literal(awaitingPlotInfo ? "INVIATO" : "PRONTO")
                        .formatted(awaitingPlotInfo ? Formatting.GOLD : Formatting.GREEN));

        int screenWidth = client.getWindow().getScaledWidth();
        int lineHeight = client.textRenderer.fontHeight + 1;
        int padding = 4;
        int line1Width = client.textRenderer.getWidth(line1);
        int line2Width = client.textRenderer.getWidth(line2);
        int line3Width = client.textRenderer.getWidth(line3);
        int boxWidth = Math.min(screenWidth - 12, Math.max(Math.max(line1Width, line2Width), line3Width) + padding * 2);
        int boxHeight = lineHeight * 3 + padding * 2;
        int x = 6;
        int y = 6;

        context.fill(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2, 0x90000000);
        context.drawTextWithShadow(client.textRenderer, line1, x + padding, y + padding, 0xFFFFFFFF);
        context.drawTextWithShadow(client.textRenderer, line2, x + padding, y + padding + lineHeight, 0xFFFFFFFF);
        context.drawTextWithShadow(client.textRenderer, line3, x + padding, y + padding + lineHeight * 2, 0xFFFFFFFF);
    }
}
