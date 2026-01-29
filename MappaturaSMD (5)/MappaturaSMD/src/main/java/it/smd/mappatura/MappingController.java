package it.smd.mappatura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final int MAX_ATTEMPTS = 3;
    private static final boolean DEBUG_THROUGHPUT = false;
    private static final long THROUGHPUT_WINDOW_MS = 60_000L;
    private static final int SUBMIT_MAX_ATTEMPTS = 5;
    private static final long SUBMIT_RETRY_BASE_DELAY_MS = 750L;
    private static final int SUBMIT_WORKERS = 2;
    private static final long SUBMIT_BLOCK_WARN_COOLDOWN_MS = 10_000L;
    private static final long SUBMIT_RETRY_WARN_COOLDOWN_MS = 5_000L;

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
    private final Set<String> pendingChunks = new HashSet<>();
    private PlotRequest inFlight;
    private final SubmitPlotQueue submitQueue;
    private long lastSubmitBlockWarnAtMs = 0L;
    private long lastSubmitRetryWarnAtMs = 0L;
    private String lastSubmitBlockReason = null;
    private boolean forceRunNextTick = false;

    private static final class PlotRequest {
        private final long requestId;
        private final Integer fallbackX;
        private final Integer fallbackZ;
        private final int chunkX;
        private final int chunkZ;
        private final int attempt;
        private final boolean priority;
        private long sentAtMs;

        private PlotRequest(long requestId, Integer fallbackX, Integer fallbackZ, int chunkX, int chunkZ, int attempt, boolean priority) {
            this.requestId = requestId;
            this.fallbackX = fallbackX;
            this.fallbackZ = fallbackZ;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
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
        pendingChunks.clear();
        inFlight = null;
        lastCommandAtMs = 0L;
        requestSeq = 0L;
        completedInWindow = 0L;
        throughputWindowStartMs = System.currentTimeMillis();
        lastChunkX = null;
        lastChunkZ = null;
        forceRunNextTick = true;
        running = true;
    }

    public void stop() {
        running = false;
        parser.forceReset();
        queue.clear();
        pendingChunks.clear();
        inFlight = null;
        lastChunkX = null;
        lastChunkZ = null;
        forceRunNextTick = false;
    }

    public void toggle() {
        if (running) stop();
        else start();
    }

    public boolean isRunning() {
        return running;
    }

    public int getPendingSubmitCount() {
        return submitQueue.getPendingCount();
    }

    public int getPendingPlotRequestCount() {
        int count = queue.size();
        if (inFlight != null) {
            count++;
        }
        return count;
    }

    public boolean isAwaitingPlotInfo() {
        return inFlight != null;
    }

    public void onTick(MinecraftClient client) {
        if (!running) return;
        if (client == null) return;

        TickGate.INSTANCE.tick();

        AppConfig cfg = ConfigManager.get();
        parser.tick(cfg != null ? cfg.parserTimeoutMs : 0L);

        int interval = cfg != null ? cfg.tickInterval : 1;
        boolean shouldRun = TickGate.INSTANCE.shouldRun(interval);
        if (forceRunNextTick) {
            shouldRun = true;
            forceRunNextTick = false;
        }
        if (!shouldRun) return;

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

        maybeEnqueueRequest(client);
        startNextIfReady(client, now, cooldown);
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
            clearPendingChunk(inFlight);
            inFlight = null;
        }

        recordThroughput();

        boolean alreadyMapped = PlotCacheManager.isPlotMapped(info);

        // 1) Salva in cache locale (persistente)
        PlotCacheManager.record(info);

        // 2) Enqueue push in background (istananeo sul gameplay)
        if (alreadyMapped) {
            HudOverlay.showBadge("⚠️ Plot già mappato (cache): " + info.plotId, HudOverlay.Badge.NEUTRAL);
            return;
        }

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
            clearPendingChunk(failed);
            HudOverlay.showBadge("❌ Timeout plot info (max retry) su chunk " + failed.chunkX + ", " + failed.chunkZ, HudOverlay.Badge.ERROR);
        }
    }

    /**
     * HOOK richiesto da ChatPlotInfoParser:
     * chiamato quando il server risponde che non siamo dentro un plot.
     */
    public void onPlotInfoRejected(String reason) {
        if (inFlight == null) return;

        PlotRequest failed = inFlight;
        inFlight = null;
        clearPendingChunk(failed);

        String detail = (reason == null || reason.isBlank()) ? "Plot info non disponibile" : reason;
        HudOverlay.showBadge("⚠️ " + detail + " (chunk " + failed.chunkX + ", " + failed.chunkZ + ")", HudOverlay.Badge.NEUTRAL);
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

    private void maybeEnqueueRequest(MinecraftClient client) {
        if (client == null || client.player == null) return;

        int chunkX = client.player.getChunkPos().x;
        int chunkZ = client.player.getChunkPos().z;
        boolean chunkChanged = lastChunkX == null || lastChunkZ == null || chunkX != lastChunkX || chunkZ != lastChunkZ;
        if (!chunkChanged) return;
        if (inFlight != null && (chunkX != inFlight.chunkX || chunkZ != inFlight.chunkZ)) {
            cancelInFlightForNewChunk();
        }
        String chunkKey = toChunkKey(chunkX, chunkZ);
        if (pendingChunks.contains(chunkKey)) {
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
            return;
        }

        int fallbackX = client.player.getBlockPos().getX();
        int fallbackZ = client.player.getBlockPos().getZ();
        PlotRequest req = new PlotRequest(++requestSeq, fallbackX, fallbackZ, chunkX, chunkZ, 1, true);
        if (queue.isEmpty()) {
            queue.add(req);
        } else {
            queue.addFirst(req);
        }
        pendingChunks.add(chunkKey);
        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
    }

    private void cancelInFlightForNewChunk() {
        parser.forceReset();
        clearPendingChunk(inFlight);
        inFlight = null;
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
        String dimension = null;
        if (client.world != null && client.world.getRegistryKey() != null) {
            dimension = client.world.getRegistryKey().getValue().getPath();
        }
        AppConfig cfg = ConfigManager.get();
        if (dimension == null || dimension.isBlank()) {
            dimension = cfg != null ? cfg.dimensionDefault : null;
        }
        parser.beginRequest(req.requestId, req.fallbackX, req.fallbackZ, dimension);
        sendCommand(client, cmd);
        lastCommandAtMs = now;
    }

    private void enqueueRetry(PlotRequest failed) {
        PlotRequest retry = new PlotRequest(
                failed.requestId,
                failed.fallbackX,
                failed.fallbackZ,
                failed.chunkX,
                failed.chunkZ,
                failed.attempt + 1,
                false
        );
        queue.addFirst(retry);
        HudOverlay.showBadge("⚠️ Timeout plot info, retry " + retry.attempt + "/" + MAX_ATTEMPTS, HudOverlay.Badge.NEUTRAL);
    }

    private void clearPendingChunk(PlotRequest request) {
        if (request == null) return;
        pendingChunks.remove(toChunkKey(request.chunkX, request.chunkZ));
    }

    private String toChunkKey(int chunkX, int chunkZ) {
        return chunkX + ":" + chunkZ;
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

    private void handleSubmitResult(PlotInfo info, SubmitPlotClient.SubmitResult result, int attempt) {
        String plotLabel = formatPlotLabel(info);
        if (result == null) {
            HudOverlay.showBadge("❌ Submit fallito per " + plotLabel + ": NETWORK_ERROR (nessuna risposta)", HudOverlay.Badge.ERROR);
            return;
        }
        if (!result.success) {
            String rawError = (result.error == null || result.error.isBlank()) ? "SUBMIT_FAILED" : result.error;
            String detail = buildSubmitFailureDetail(result);
            if ("NETWORK_ERROR".equals(rawError) && result.debug != null && result.debug.has("exception")) {
                String exception = result.debug.get("exception").getAsString();
                HudOverlay.showBadge("❌ Submit fallito per " + plotLabel + ": NETWORK_ERROR (" + exception + ")", HudOverlay.Badge.ERROR);
                return;
            }
            if (result.httpStatus == 403 && "NOT_WHITELISTED".equalsIgnoreCase(rawError)) {
                HudOverlay.showBadge("⛔ Submit bloccato per " + plotLabel + ": richiedi la whitelist.", HudOverlay.Badge.ERROR);
                return;
            }
            if (result.httpStatus == 404) {
                HudOverlay.showBadge("❌ Submit fallito per " + plotLabel + ": sessione non trovata o non attiva.", HudOverlay.Badge.ERROR);
                return;
            }
            String retryNote = attempt >= SUBMIT_MAX_ATTEMPTS ? " (tentativi esauriti)" : "";
            HudOverlay.showBadge("❌ Submit fallito per " + plotLabel + ": " + detail + retryNote, HudOverlay.Badge.ERROR);
            return;
        }
        if (result.alreadyMapped) {
            HudOverlay.showBadge("⚠️ Plot già presente: " + info.plotId, HudOverlay.Badge.NEUTRAL);
            return;
        }
        HudOverlay.showBadge("✅ Plot inviato: " + info.plotId + " (" + info.coordX + ", " + info.coordZ + ")", HudOverlay.Badge.OK);
    }

    private boolean canSubmitNow(boolean showHud) {
        String blockReason = getSubmitBlockReason();
        if (blockReason == null) return true;
        if (showHud) {
            HudOverlay.showBadge("❌ " + blockReason, HudOverlay.Badge.ERROR);
        }
        return false;
    }

    private String getSubmitBlockReason() {
        AppConfig cfg = ConfigManager.get();
        String endpoint = cfg != null ? SubmitPlotClient.normalizeUrl(cfg.endpointUrl) : "";
        if (endpoint.isBlank()) {
            return "Endpoint mancante";
        }
        String sessionCode = cfg != null ? cfg.sessionCode : null;
        if (sessionCode == null || sessionCode.isBlank()) {
            return "Codice sessione mancante";
        }
        return null;
    }

    private void notifySubmitBlocked(String reason) {
        long now = System.currentTimeMillis();
        if (reason == null || reason.isBlank()) return;
        if (reason.equals(lastSubmitBlockReason) && now - lastSubmitBlockWarnAtMs < SUBMIT_BLOCK_WARN_COOLDOWN_MS) {
            return;
        }
        lastSubmitBlockReason = reason;
        lastSubmitBlockWarnAtMs = now;
        dispatchToMainThread(() -> HudOverlay.showBadge("⚠️ Invio sospeso: " + reason + ".", HudOverlay.Badge.ERROR));
    }

    private void notifySubmitRetry(SubmitTask task, String detail, long delayMs) {
        long now = System.currentTimeMillis();
        if (now - lastSubmitRetryWarnAtMs < SUBMIT_RETRY_WARN_COOLDOWN_MS) return;
        lastSubmitRetryWarnAtMs = now;
        String reason = (detail == null || detail.isBlank()) ? "errore sconosciuto" : detail;
        String message = "⚠️ Invio fallito (" + reason + "). Riprovo " + (task.attempt + 1) + "/" + SUBMIT_MAX_ATTEMPTS
                + " per " + formatPlotLabel(task.info) + " tra " + (delayMs / 1000) + "s.";
        dispatchToMainThread(() -> HudOverlay.showBadge(message, HudOverlay.Badge.NEUTRAL));
    }

    private String buildSubmitFailureDetail(SubmitPlotClient.SubmitResult result) {
        if (result == null) return "NETWORK_ERROR";
        String error = (result.error == null || result.error.isBlank()) ? "SUBMIT_FAILED" : result.error;
        if ("NETWORK_ERROR".equals(error) && result.debug != null && result.debug.has("exception")) {
            error += " (" + result.debug.get("exception").getAsString() + ")";
        }
        if (result.httpStatus > 0 && !error.startsWith("HTTP_")) {
            error += " [HTTP " + result.httpStatus + "]";
        }
        return error;
    }

    private String formatPlotLabel(PlotInfo info) {
        if (info == null) return "plot sconosciuto";
        return info.plotId + " (" + info.coordX + ", " + info.coordZ + ")";
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
        private final String key;

        private SubmitTask(PlotInfo info) {
            this.info = info;
            this.attempt = 1;
            this.key = buildSubmitKey(info);
        }
    }

    private final class SubmitPlotQueue implements Runnable {
        private final BlockingQueue<SubmitTask> queue = new LinkedBlockingQueue<>();
        private final ConcurrentHashMap<String, SubmitTask> pendingByKey = new ConcurrentHashMap<>();

        private SubmitPlotQueue() {
            List<PlotInfo> pending = SubmitQueueStorage.loadPending();
            if (!pending.isEmpty()) {
                for (PlotInfo info : pending) {
                    enqueueInternal(new SubmitTask(info), false);
                }
            }
            for (int i = 1; i <= SUBMIT_WORKERS; i++) {
                Thread worker = new Thread(this, "SMD-SubmitQueue-" + i);
                worker.setDaemon(true);
                worker.start();
            }
        }

        private void enqueue(SubmitTask task) {
            if (task == null || task.info == null) return;
            enqueueInternal(task, true);
        }

        private void enqueueInternal(SubmitTask task, boolean persist) {
            if (task == null || task.info == null) return;
            SubmitTask existing = pendingByKey.putIfAbsent(task.key, task);
            if (existing != null) return;
            queue.offer(task);
            if (persist) persistPending();
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
            while (!canSubmitNow(false)) {
                String reason = getSubmitBlockReason();
                notifySubmitBlocked(reason != null ? reason : "configurazione incompleta");
                Thread.sleep(SUBMIT_RETRY_BASE_DELAY_MS);
            }
            SubmitPlotClient.SubmitResult result = SubmitPlotClient.submitBlocking(task.info);
            if (shouldRetry(result) && task.attempt < SUBMIT_MAX_ATTEMPTS) {
                long delay = SUBMIT_RETRY_BASE_DELAY_MS * (1L << Math.max(0, task.attempt - 1));
                notifySubmitRetry(task, buildSubmitFailureDetail(result), delay);
                Thread.sleep(delay);
                task.attempt++;
                queue.offer(task);
                return;
            }
            if (result != null && (result.success || result.alreadyMapped)) {
                pendingByKey.remove(task.key);
                persistPending();
            }
            int attemptSnapshot = task.attempt;
            dispatchToMainThread(() -> handleSubmitResult(task.info, result, attemptSnapshot));
        }

        private boolean shouldRetry(SubmitPlotClient.SubmitResult result) {
            if (result == null) return true;
            if (result.success) return false;
            if (result.error != null && result.error.startsWith("NETWORK_ERROR")) return true;
            return result.httpStatus == 429 || result.httpStatus >= 500;
        }

        private void persistPending() {
            List<PlotInfo> snapshot = new ArrayList<>();
            for (SubmitTask task : pendingByKey.values()) {
                if (task != null && task.info != null) snapshot.add(task.info);
            }
            SubmitQueueStorage.savePending(snapshot);
        }

        private int getPendingCount() {
            return pendingByKey.size();
        }
    }

    private static String buildSubmitKey(PlotInfo info) {
        if (info == null) return "";
        return info.plotId + "|" + info.coordX + "|" + info.coordZ;
    }
}
