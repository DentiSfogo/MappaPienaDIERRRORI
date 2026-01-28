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

        // ✅ Cache persistente (non si svuota tra riavvii)
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

            if (authorized) {
                HudOverlay.show(Text.literal("§a[SMD] Whitelist confermata. Mod attiva."));
            } else {
                HudOverlay.show(Text.literal("§e[SMD] Non whitelistato. Usa /richiestawhitelist e poi /mappatura refresh"));
            }
        });
    }

    public static boolean isAuthorized() {
        return authorized;
    }
}
