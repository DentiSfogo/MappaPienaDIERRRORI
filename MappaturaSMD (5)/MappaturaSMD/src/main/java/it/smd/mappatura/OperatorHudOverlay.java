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
        boolean running = controller != null && controller.isRunning();

        MutableText line1 = Text.literal("Operatore: ").formatted(Formatting.GRAY)
                .append(Text.literal(operator).formatted(Formatting.WHITE))
                .append(Text.literal(" | Stato: ").formatted(Formatting.GRAY))
                .append(Text.literal(running ? "ON" : "OFF").formatted(running ? Formatting.GREEN : Formatting.RED));

        MutableText line2 = Text.literal("Plot mappati: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(mappedCount)).formatted(Formatting.AQUA))
                .append(Text.literal(" | In coda: ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(pendingCount)).formatted(Formatting.YELLOW));

        int screenWidth = client.getWindow().getScaledWidth();
        int lineHeight = client.textRenderer.fontHeight + 1;
        int padding = 4;
        int line1Width = client.textRenderer.getWidth(line1);
        int line2Width = client.textRenderer.getWidth(line2);
        int boxWidth = Math.min(screenWidth - 12, Math.max(line1Width, line2Width) + padding * 2);
        int boxHeight = lineHeight * 2 + padding * 2;
        int x = 6;
        int y = 6;

        context.fill(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2, 0x90000000);
        context.drawTextWithShadow(client.textRenderer, line1, x + padding, y + padding, 0xFFFFFF);
        context.drawTextWithShadow(client.textRenderer, line2, x + padding, y + padding + lineHeight, 0xFFFFFF);
    }
}
