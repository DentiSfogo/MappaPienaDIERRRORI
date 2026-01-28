package it.smd.mappatura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class HudOverlay {
    public static void show(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) return;

        // thread-safe: sempre sul main thread
        client.execute(() -> client.inGameHud.setOverlayMessage(text, false));
    }
}
