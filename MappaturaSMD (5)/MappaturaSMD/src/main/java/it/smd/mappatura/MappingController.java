package it.smd.mappatura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Controller principale della mappatura.
 *
 * NOTA: Questo file serve solo a ripristinare la compilazione e gli hook
 * richiesti da ChatPlotInfoParser (onPlotInfoReady / onPlotInfoTimeout).
 *
 * Se nel tuo progetto originale avevi altra logica qui dentro,
 * puoi reintegrarla mantenendo SEMPRE questi due metodi pubblici con la stessa firma.
 */
public class MappingController {

    private boolean running = false;

    public void start() {
        // Se vuoi bloccare la mappatura quando non whitelistato, sblocca queste righe:
        // if (!MappaturaSMDClient.isAuthorized()) {
        //     sendChat("§cNon sei whitelistato. Usa /richiestawhitelist e poi /mappatura refresh");
        //     return;
        // }

        running = true;
    }

    public void stop() {
        running = false;
    }

    public void toggle() {
        if (running) stop();
        else start();
    }

    public boolean isRunning() {
        return running;
    }

    public void onTick(MinecraftClient client) {
        if (!running) return;
        // Logica mapping (lasciata invariata/da integrare nel tuo progetto)
    }

    public void onChat(Text message) {
        if (!running) return;
        // Logica parsing chat (lasciata invariata/da integrare nel tuo progetto)
    }

    /**
     * HOOK richiesto da ChatPlotInfoParser:
     * viene chiamato quando il parser ha ricostruito un PlotInfo completo.
     */
    public void onPlotInfoReady(PlotInfo info) {
        if (info == null) return;

        // 1) Salva in cache locale (persistente)
        PlotCacheManager.record(info);

        // 2) Invia al backend (submitPlot)
        SubmitPlotClient.submitAsync(
                info,
                ok -> {
                    if (ok != null && ok) {
                        HudOverlay.show(Text.literal("✅ Plot salvato: " + info.plotId + " (" + info.coordX + ", " + info.coordZ + ")"));
                    } else {
                        HudOverlay.show(Text.literal("⚠️ Plot già presente: " + info.plotId));
                    }
                },
                err -> {
                    String e = (err == null || err.isBlank()) ? "Errore sconosciuto" : err;
                    HudOverlay.show(Text.literal("❌ Submit fallito: " + e));
                }
        );
    }

    /**
     * HOOK richiesto da ChatPlotInfoParser:
     * chiamato quando scade il timeout mentre si stava raccogliendo plot info.
     */
    public void onPlotInfoTimeout() {
        // Hook di sicurezza: se serve, qui puoi resettare stati interni del controller
        // (il parser si resetta già da solo).
    }

    private void sendChat(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), false);
        }
    }
}
