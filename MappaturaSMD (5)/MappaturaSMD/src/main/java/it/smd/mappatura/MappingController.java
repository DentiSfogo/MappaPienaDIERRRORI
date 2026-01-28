package it.smd.mappatura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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

    private static final int MAX_QUEUE = 8;
    private static final int MAX_ATTEMPTS = 3;
    private static final boolean DEBUG_THROUGHPUT = false;
    private static final long THROUGHPUT_WINDOW_MS = 60_000L;
    private static final int SUBMIT_MAX_ATTEMPTS = 5;
    private static final long SUBMIT_RETRY_BASE_DELAY_MS = 750L;

    private boolean running = false;
    private final ChatPlotInfoParser parser;
    private boolean missingSessionWarned = false;
    private long lastCommandAtMs = 0L;
    private long requestSeq = 0L;
    private long completedInWindow = 0L;
    private long throughputWindowStartMs = 0L;
    private Integer lastChunkX = null;
    private Integer lastChunkZ = null;
    private final Deque<PlotRequest> queue = new ArrayDeque<>();
    private PlotRequest inFlight;
    private final SubmitPlotQueue submitQueue;

    private static final class PlotRequest {
        private final long requestId;
        private final Integer fallbackX;
        private final Integer fallbackZ;
        private final int attempt;
        private final boolean priority;
        private long sentAtMs;

        private PlotRequest(long requestId, Integer fallbackX, Integer fallbackZ, int attempt, boolean priority) {
            this.requestId = requestId;
            this.fallbackX = fallbackX;
            this.fallbackZ = fallbackZ;
            this.attempt = attempt;
            this.priority = priority;
        }
    }

    public MappingController() {
        this.parser = new ChatPlotInfoParser(this);
        this.submitQueue = new SubmitPlotQueue();
    }

    public void start() {
        // Se vuoi bloccare la mappatura quando non whitelistato, sblocca queste righe:
        // if (!MappaturaSMDClient.isAuthorized()) {
        //     sendChat("§cNon sei whitelistato. Usa /richiestawhitelist e poi /mappatura refresh");
        //     return;
        // }
        parser.forceReset();
        missingSessionWarned = false;
        queue.clear();
        inFlight = null;
        lastCommandAtMs = 0L;
        requestSeq = 0L;
        completedInWindow = 0L;
        throughputWindowStartMs = System.currentTimeMillis();
        lastChunkX = null;
        lastChunkZ = null;
        running = true;
    }

    public void stop() {
        running = false;
        parser.forceReset();
        queue.clear();
        inFlight = null;
        lastChunkX = null;
        lastChunkZ = null;
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
                HudOverlay.showBadge("⚠️ Inserisci un codice sessione prima di avviare la mappatura.", HudOverlay.Badge.ERROR);
            }
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = cfg != null ? cfg.commandCooldownMs : 600L;
        if (cooldown < 0) cooldown = 0;

        boolean sentImmediate = maybeStartImmediateRequest(client, now);
        if (!sentImmediate) {
            startNextIfReady(client, now, cooldown);
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

        if (inFlight != null && info.requestId != inFlight.requestId) {
            return;
        }

        if (inFlight != null) {
            inFlight = null;
        }

        recordThroughput();

        // 1) Salva in cache locale (persistente)
        PlotCacheManager.record(info);

        // 2) Enqueue push in background (istananeo sul gameplay)
        if (!canSubmitNow(true)) {
            submitQueue.enqueue(new SubmitTask(info));
            HudOverlay.showBadge("⏱️ Plot in coda: " + info.plotId + " (" + info.coordX + ", " + info.coordZ + ")", HudOverlay.Badge.NEUTRAL);
            return;
        }
        submitQueue.enqueue(new SubmitTask(info));
        HudOverlay.showBadge("⏱️ Plot in coda: " + info.plotId + " (" + info.coordX + ", " + info.coordZ + ")", HudOverlay.Badge.NEUTRAL);
    }

    /**
     * HOOK richiesto da ChatPlotInfoParser:
     * chiamato quando scade il timeout mentre si stava raccogliendo plot info.
     */
    public void onPlotInfoTimeout() {
        if (inFlight == null) return;

        PlotRequest failed = inFlight;
        inFlight = null;

        if (failed.attempt < MAX_ATTEMPTS) {
            enqueueRetry(failed);
        } else {
            HudOverlay.showBadge("❌ Timeout plot info (max retry)", HudOverlay.Badge.ERROR);
        }
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

    private boolean maybeStartImmediateRequest(MinecraftClient client, long now) {
        if (client == null || client.player == null) return false;

        int chunkX = client.player.getChunkPos().x;
        int chunkZ = client.player.getChunkPos().z;
        boolean chunkChanged = lastChunkX == null || lastChunkZ == null || chunkX != lastChunkX || chunkZ != lastChunkZ;
        if (!chunkChanged) return false;

        int fallbackX = client.player.getBlockPos().getX();
        int fallbackZ = client.player.getBlockPos().getZ();
        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        if (inFlight == null) {
            PlotRequest req = new PlotRequest(++requestSeq, fallbackX, fallbackZ, 1, true);
            inFlight = req;
            inFlight.sentAtMs = now;
            String cmd = ConfigManager.get() != null ? ConfigManager.get().plotInfoCommand : "plot info";
            sendCommand(client, cmd);
            parser.beginRequest(req.requestId, req.fallbackX, req.fallbackZ);
            lastCommandAtMs = now;
            return true;
        }
        if (queue.size() >= MAX_QUEUE) return false;
        PlotRequest req = new PlotRequest(++requestSeq, fallbackX, fallbackZ, 1, true);
        queue.addFirst(req);
        return false;
    }

    private void startNextIfReady(MinecraftClient client, long now, long cooldownMs) {
        if (inFlight != null) return;
        if (queue.isEmpty()) return;
        if (!queue.peek().priority && now - lastCommandAtMs < cooldownMs) return;

        PlotRequest req = queue.poll();
        if (req == null) return;
        inFlight = req;
        inFlight.sentAtMs = now;
        String cmd = ConfigManager.get() != null ? ConfigManager.get().plotInfoCommand : "plot info";
        sendCommand(client, cmd);
        parser.beginRequest(req.requestId, req.fallbackX, req.fallbackZ);
        lastCommandAtMs = now;
    }

    private void enqueueRetry(PlotRequest failed) {
        if (queue.size() >= MAX_QUEUE) return;
        PlotRequest retry = new PlotRequest(failed.requestId, failed.fallbackX, failed.fallbackZ, failed.attempt + 1, false);
        queue.addFirst(retry);
        HudOverlay.showBadge("⚠️ Timeout plot info, retry " + retry.attempt + "/" + MAX_ATTEMPTS, HudOverlay.Badge.NEUTRAL);
    }

    private void recordThroughput() {
        if (!DEBUG_THROUGHPUT) return;
        long now = System.currentTimeMillis();
        if (throughputWindowStartMs == 0L) throughputWindowStartMs = now;
        completedInWindow++;
        if (now - throughputWindowStartMs >= THROUGHPUT_WINDOW_MS) {
            long minutes = Math.max(1, (now - throughputWindowStartMs) / 60_000L);
            System.out.println("[SMD] Throughput: " + (completedInWindow / minutes) + " plot/min");
            throughputWindowStartMs = now;
            completedInWindow = 0L;
        }
    }

    private void handleSubmitResult(PlotInfo info, SubmitPlotClient.SubmitResult result) {
        if (result == null) {
            HudOverlay.showBadge("❌ Submit fallito: NETWORK_ERROR", HudOverlay.Badge.ERROR);
            return;
        }
        if (!result.success) {
            String e = (result.error == null || result.error.isBlank()) ? "SUBMIT_FAILED" : result.error;
            if ("NETWORK_ERROR".equals(e) && result.debug != null && result.debug.has("exception")) {
                String detail = result.debug.get("exception").getAsString();
                HudOverlay.showBadge("❌ Submit fallito: NETWORK_ERROR (" + detail + ")", HudOverlay.Badge.ERROR);
                return;
            }
            if (result.httpStatus == 403 && "NOT_WHITELISTED".equalsIgnoreCase(e)) {
                HudOverlay.showBadge("⛔ Accesso negato: richiedi la whitelist.", HudOverlay.Badge.ERROR);
                return;
            }
            if (result.httpStatus == 404) {
                HudOverlay.showBadge("❌ Sessione non trovata o non attiva.", HudOverlay.Badge.ERROR);
                return;
            }
            HudOverlay.showBadge("❌ Submit fallito: " + e, HudOverlay.Badge.ERROR);
            return;
        }
        if (result.alreadyMapped) {
            HudOverlay.showBadge("⚠️ Plot già presente: " + info.plotId, HudOverlay.Badge.NEUTRAL);
            return;
        }
        HudOverlay.showBadge("✅ Plot inviato: " + info.plotId + " (" + info.coordX + ", " + info.coordZ + ")", HudOverlay.Badge.OK);
    }

    private boolean canSubmitNow(boolean showHud) {
        AppConfig cfg = ConfigManager.get();
        String endpoint = cfg != null ? SubmitPlotClient.normalizeUrl(cfg.endpointUrl) : "";
        if (endpoint.isBlank()) {
            if (showHud) {
                HudOverlay.showBadge("❌ Endpoint mancante", HudOverlay.Badge.ERROR);
            }
            return false;
        }
        String sessionCode = cfg != null ? cfg.sessionCode : null;
        if (sessionCode == null || sessionCode.isBlank()) {
            if (showHud) {
                HudOverlay.showBadge("❌ Codice sessione mancante", HudOverlay.Badge.ERROR);
            }
            return false;
        }
        return true;
    }

    private void dispatchToMainThread(Runnable task) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.execute(task);
        } else {
            task.run();
        }
    }

    private static final class SubmitTask {
        private final PlotInfo info;
        private int attempt;

        private SubmitTask(PlotInfo info) {
            this.info = info;
            this.attempt = 1;
        }
    }

    private final class SubmitPlotQueue implements Runnable {
        private final BlockingQueue<SubmitTask> queue = new LinkedBlockingQueue<>();

        private SubmitPlotQueue() {
            Thread worker = new Thread(this, "SMD-SubmitQueue");
            worker.setDaemon(true);
            worker.start();
        }

        private void enqueue(SubmitTask task) {
            if (task == null || task.info == null) return;
            queue.offer(task);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    SubmitTask task = queue.take();
                    handleSubmitTask(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void handleSubmitTask(SubmitTask task) throws InterruptedException {
            if (!canSubmitNow(false)) {
                Thread.sleep(SUBMIT_RETRY_BASE_DELAY_MS);
                queue.offer(task);
                return;
            }
            SubmitPlotClient.SubmitResult result = SubmitPlotClient.submitBlocking(task.info);
            if (shouldRetry(result) && task.attempt < SUBMIT_MAX_ATTEMPTS) {
                long delay = SUBMIT_RETRY_BASE_DELAY_MS * (1L << Math.max(0, task.attempt - 1));
                Thread.sleep(delay);
                task.attempt++;
                queue.offer(task);
                return;
            }
            dispatchToMainThread(() -> handleSubmitResult(task.info, result));
        }

        private boolean shouldRetry(SubmitPlotClient.SubmitResult result) {
            if (result == null) return true;
            if (result.success) return false;
            if (result.error != null && result.error.startsWith("NETWORK_ERROR")) return true;
            return result.httpStatus == 429 || result.httpStatus >= 500;
        }
    }
}
