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
    private final ChatPlotInfoParser parser;
    private long lastCommandAtMs = 0L;
    private boolean missingSessionWarned = false;

    public MappingController() {
        this.parser = new ChatPlotInfoParser(this);
    }

    public void start() {
        // Se vuoi bloccare la mappatura quando non whitelistato, sblocca queste righe:
        // if (!MappaturaSMDClient.isAuthorized()) {
        //     sendChat("§cNon sei whitelistato. Usa /richiestawhitelist e poi /mappatura refresh");
        //     return;
        // }
        parser.forceReset();
        missingSessionWarned = false;
        running = true;
    }

    public void stop() {
        running = false;
        parser.forceReset();
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
        if (client == null) return;

        TickGate.INSTANCE.tick();

        AppConfig cfg = ConfigManager.get();
        parser.tick(cfg != null ? cfg.parserTimeoutMs : 0L);

        int interval = cfg != null ? cfg.tickInterval : 1;
        if (!TickGate.INSTANCE.shouldRun(interval)) return;

        if (client.player == null || client.getNetworkHandler() == null) return;

        String sessionCode = cfg != null && cfg.sessionCode != null ? cfg.sessionCode.trim() : "";
        if (sessionCode.isBlank()) {
            if (!missingSessionWarned) {
                missingSessionWarned = true;
                HudOverlay.show(Text.literal("⚠️ Inserisci un codice sessione prima di avviare la mappatura."));
            }
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = cfg != null ? cfg.commandCooldownMs : 600L;
        if (cooldown < 0) cooldown = 0;
        if (now - lastCommandAtMs < cooldown) return;

        if (!parser.isCollecting()) {
            String cmd = cfg != null ? cfg.plotInfoCommand : "plot info";
            sendCommand(client, cmd);
            parser.beginRequest();
            lastCommandAtMs = now;
        }
    }

    public void onChat(Text message) {
        if (!running) return;
        if (message == null) return;
        parser.onChatLine(message.getString());
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
                    if (ok != null && !ok.alreadyMapped) {
                        HudOverlay.show(Text.literal("✅ Plot salvato: " + info.plotId + " (" + info.coordX + ", " + info.coordZ + ")"));
                    } else if (ok != null && ok.alreadyMapped) {
                        HudOverlay.show(Text.literal("⚠️ Plot già presente: " + info.plotId));
                    } else {
                        HudOverlay.show(Text.literal("⚠️ Submit completato ma senza risposta valida."));
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

    private void sendCommand(MinecraftClient client, String command) {
        if (client == null || client.getNetworkHandler() == null) return;
        if (command == null || command.isBlank()) return;

        String cmd = command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isBlank()) return;

        client.getNetworkHandler().sendChatCommand(cmd);
    }

    private void sendChat(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), false);
        }
    }
}
