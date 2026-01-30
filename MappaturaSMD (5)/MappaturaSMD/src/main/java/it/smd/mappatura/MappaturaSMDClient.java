package it.smd.mappatura;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class MappaturaSMDClient implements ClientModInitializer {

    private static MappingController controller;

    private static boolean authorized = false;
    private static boolean checkedOnce = false;

    @Override
    public void onInitializeClient() {
        ConfigManager.init();

        // ✅ Cache solo per la sessione (si svuota quando esci/rientri)
        PlotCacheManager.init();

        // ✅ Comandi /mappatura (cerca/copia/svuotaCache/refresh/richiestawhitelist)
        MappaturaCommands.register();

        controller = new MappingController();

        KeyBinding openPanelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mappaturasmd.open_panel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.mappaturasmd"
        ));

        KeyBinding toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mappaturasmd.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.mappaturasmd"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // Primo check automatico appena il player è disponibile
            if (client.player != null && !checkedOnce) {
                checkedOnce = true;
                refreshAuthorization();
            }

            while (openPanelKey.wasPressed())
                MinecraftClient.getInstance().setScreen(new MappaturaScreen(controller));

            while (toggleKey.wasPressed())
                controller.toggle();

            controller.onTick(client);
        });

        // chat/game messages -> parser
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> controller.onChat(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> controller.onChat(message));

        // stop automatico quando slogghi
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (controller != null && controller.isRunning()) controller.stop();
            checkedOnce = false;
            authorized = false;
            PlotCacheManager.clear();
        });

        // auto-start quando entri, se impostato e codice sessione presente
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            AppConfig cfg = ConfigManager.get();
            boolean hasSession = cfg.sessionCode != null && !cfg.sessionCode.isBlank();
            if (cfg.autoStart && hasSession) {
                HudOverlay.show(Text.literal("▶ Auto-start mappatura..."));
                controller.start();
            }
        });

        HudOverlay.show(Text.literal("Mappatura SMD pronta. Premi O per aprire il pannello."));
    }

    /**
     * Richiama /functions/checkAccess per verificare se l'operatore è whitelistato.
     *
     * Firma SubmitPlotClient.checkAccessAsync:
     *   checkAccessAsync(String publishCode, Consumer<AuthResult> cb)
     */
    public static void refreshAuthorization() {
        AppConfig cfg = ConfigManager.get();
        String publishCode = (cfg != null && cfg.sessionCode != null) ? cfg.sessionCode.trim() : "";

        SubmitPlotClient.checkAccessAsync(publishCode, result -> {
            authorized = (result != null && result.authorized);
            boolean networkError = result == null
                    || "NETWORK_ERROR".equals(result.reason)
                    || (result.debug != null && result.debug.has("exception"));

            String message = authorized
                    ? "§a[SMD] Whitelist confermata. Mod attiva."
                    : (networkError
                        ? "§c[SMD] Errore di rete. Controlla connessione/endpoint."
                        : "§e[SMD] Non whitelistato. Usa /richiestawhitelist e poi /mappatura refresh");

            HudOverlay.show(Text.literal(message));

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    AppConfig updated = ConfigManager.get();
                    if (updated != null) {
                        updated.authorized = authorized;
                        String fallback = authorized ? "OK" : "NOT_AUTHORIZED";
                        String reason = result != null && result.reason != null ? result.reason : fallback;
                        if (networkError && result != null && result.debug != null && result.debug.has("exception")) {
                            reason = "NETWORK_ERROR (" + result.debug.get("exception").getAsString() + ")";
                        }
                        updated.lastAuthMessage = reason;
                        ConfigManager.save();
                    }
                });
            }
        });
    }

    public static boolean isAuthorized() {
        return authorized;
    }
}
