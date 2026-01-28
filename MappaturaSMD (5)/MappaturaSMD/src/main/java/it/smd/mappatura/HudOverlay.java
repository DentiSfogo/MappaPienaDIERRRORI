package it.smd.mappatura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class HudOverlay {
    public enum Badge {
        OK(Formatting.GREEN),
        NEUTRAL(Formatting.GRAY),
        ERROR(Formatting.RED);

        private final Formatting formatting;

        Badge(Formatting formatting) {
            this.formatting = formatting;
        }
    }

    public static void show(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) return;

        // thread-safe: sempre sul main thread
        client.execute(() -> client.inGameHud.setOverlayMessage(text, false));
    }

    public static void showBadge(String message, Badge badge) {
        if (message == null) return;
        Formatting fmt = badge != null ? badge.formatting : Formatting.GRAY;
        show(Text.literal(message).formatted(fmt));
    }
}
