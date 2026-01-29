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
        if (client == null || client.player == null || context == null) return;
        if (controller == null) return;

        String operator = client.getSession() != null ? client.getSession().getUsername() : "Operatore";
        int mappedCount = PlotCacheManager.getMappedCount();
        int pendingCount = controller.getPendingSubmitCount();
        boolean running = controller.isRunning();

        MutableText line = Text.literal("Operatore: ").formatted(Formatting.GRAY)
                .append(Text.literal(operator).formatted(Formatting.WHITE))
                .append(Text.literal(" | Plot mappati: ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(mappedCount)).formatted(Formatting.AQUA))
                .append(Text.literal(" | In coda: ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(pendingCount)).formatted(Formatting.YELLOW))
                .append(Text.literal(" | Stato: ").formatted(Formatting.GRAY))
                .append(Text.literal(running ? "ON" : "OFF").formatted(running ? Formatting.GREEN : Formatting.RED));

        int screenWidth = client.getWindow().getScaledWidth();
        int textWidth = client.textRenderer.getWidth(line);
        int x = Math.max(4, (screenWidth - textWidth) / 2);
        int y = 6;
        context.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFFFFF);
    }
}
