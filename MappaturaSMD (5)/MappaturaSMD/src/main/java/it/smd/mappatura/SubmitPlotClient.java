package it.smd.mappatura;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SubmitPlotClient (Java 17 / Fabric)
 *
 * Questo file è pensato per essere "drop-in": contiene TUTTI i metodi
 * referenziati dagli altri file del progetto (submitAsync, searchPlotAsync,
 * checkAccessAsync overload, normalizeUrl/derive*, requestWhitelistAsync, ecc.).
 *
 * IMPORTANTISSIMO:
 * - Deve essere l'UNICO contenuto del file SubmitPlotClient.java
 * - Deve iniziare con questa riga "package ..." e finire con l'ultima "}"
 * - Non aggiungere/incollare altro sotto.
 */
public class SubmitPlotClient {

    // ====== Config ======
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 500;
    private static final long TOKEN_TTL_MS = Duration.ofHours(24).toMillis();

    // ====== Models ======
    public static final class AuthResult {
        public boolean authorized;
        public String reason;
        public JsonObject debug;
        public String session_id;
        public String session_name;
    }

    public static final class SubmitResult {
        public boolean success;
        public boolean alreadyMapped;
        public String plot_key;
        public String session_id;
        public String timestamp;
        public String error;
        public JsonObject debug;
        public int httpStatus;
    }

    public static final class SearchResult {
        public boolean success;
        public String used_publish_code;
        public JsonArray results;
        public String error;
        public JsonObject debug;
        public int httpStatus;
    }

    public static final class WhitelistRequestResult {
        public boolean success;
        public String status;     // PENDING / ALREADY_PENDING / ALREADY_WHITELISTED
        public String error;      // in caso di errore
        public String message;    // in caso di errore server
        public int httpStatus;
    }

    // ====== Public helpers (usati da /mappatura debug) ======
    public static String getOperatorName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getSession() == null) return "unknown";
        return mc.getSession().getUsername();
    }

    public static String getOperatorUuid() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getSession() == null) return null;
        UUID u = mc.getSession().getUuidOrNull();
        return u == null ? null : u.toString().replace("-", "");
    }

    public static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.contains(".base44.app")) {
            s = s.replace(".base44.app", ".base44.com");
        }
        // se finisce con "/", toglilo
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        // se arriva un endpoint completo (/functions/submitPlot o simili), ricava il base
        String[] suffixes = {
                "/functions/submitPlot",
                "/functions/checkAccess",
                "/functions/searchPlot",
                "/functions/whitelistRequest"
        };
        for (String suffix : suffixes) {
            if (s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        return s;
    }

    public static String deriveCheckAccessUrl(String endpointBase) {
        return normalizeUrl(endpointBase) + "/functions/checkAccess";
    }

    public static String deriveSearchPlotUrl(String endpointBase) {
        return normalizeUrl(endpointBase) + "/functions/searchPlot";
    }

    public static String deriveSubmitPlotUrl(String endpointBase) {
        return normalizeUrl(endpointBase) + "/functions/submitPlot";
    }

    public static String deriveWhitelistRequestUrl(String endpointBase) {
        return normalizeUrl(endpointBase) + "/functions/whitelistRequest";
    }

    // ====== API calls ======

    /**
     * Overload usato in MappaturaSMDClient / MappaturaScreen:
     * checkAccessAsync(publishCode, cb)
     */
    public static void checkAccessAsync(String publishCode, Consumer<AuthResult> cb) {
        String base = getNormalizedEndpoint();
        if (base.isBlank()) {
            if (cb != null) cb.accept(null);
            return;
        }
        String url = deriveCheckAccessUrl(base);

        JsonObject body = new JsonObject();
        addOperatorInfo(body);
        if (publishCode != null && !publishCode.isBlank()) body.addProperty("publish_code", publishCode);

        postJson(url, body, AuthResult.class, cb);
    }

    /**
     * Overload comodo: senza publish_code
     */
    public static void checkAccessAsync(Consumer<AuthResult> cb) {
        checkAccessAsync(null, cb);
    }

    public static void requestWhitelistAsync(Consumer<WhitelistRequestResult> cb) {
        String base = getNormalizedEndpoint();
        if (base.isBlank()) {
            if (cb != null) cb.accept(null);
            return;
        }
        String url = deriveWhitelistRequestUrl(base);

        JsonObject body = new JsonObject();
        addOperatorInfo(body);

        postJson(url, body, WhitelistRequestResult.class, cb);
    }

    public static void searchPlotAsync(String nome, Consumer<SearchResult> cb) {
        String base = getNormalizedEndpoint();
        if (base.isBlank()) {
            if (cb != null) cb.accept(null);
            return;
        }
        String url = deriveSearchPlotUrl(base);

        AppConfig cfg = ConfigManager.get();
        String publishCode = cfg != null ? cfg.sessionCode : null;

        JsonObject body = new JsonObject();
        addOperatorInfo(body);

        body.addProperty("search_query", nome);
        if (publishCode != null && !publishCode.isBlank()) body.addProperty("publish_code", publishCode);

        postJson(url, body, SearchResult.class, cb);
    }

    public static void submitAsync(PlotInfo info, Consumer<SubmitResult> ok, Consumer<String> err) {
        String base = getNormalizedEndpoint();
        if (base.isBlank()) {
            if (err != null) err.accept("ENDPOINT_MISSING");
            return;
        }
        String url = deriveSubmitPlotUrl(base);

        AppConfig cfg = ConfigManager.get();
        String publishCode = cfg != null ? cfg.sessionCode : null;

        if (publishCode == null || publishCode.isBlank()) {
            if (err != null) err.accept("SESSION_CODE_MISSING");
            return;
        }

        JsonObject plot = new JsonObject();
        plot.addProperty("plot_id", info.plotId);
        plot.addProperty("coord_x", info.coordX);
        plot.addProperty("coord_z", info.coordZ);
        if (info.dimension != null && !info.dimension.isBlank()) {
            plot.addProperty("dimension", info.dimension);
        } else if (cfg != null && cfg.dimensionDefault != null && !cfg.dimensionDefault.isBlank()) {
            plot.addProperty("dimension", cfg.dimensionDefault);
        }
        if (info.proprietario != null) plot.addProperty("proprietario", info.proprietario);
        if (info.ultimoAccessoIso != null) plot.addProperty("ultimo_accesso", info.ultimoAccessoIso);

        String authToken = resolveAuthToken(cfg);
        if (authToken == null || authToken.isBlank()) {
            if (err != null) err.accept("TOKEN_MISSING");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("publish_code", publishCode);
        addOperatorInfo(body);
        body.add("plot_data", plot);

        postJsonWithRetry(url, body, authToken, SubmitResult.class, r -> {
            if (r == null) {
                if (err != null) err.accept("NETWORK_ERROR");
                return;
            }
            if (!r.success) {
                if (err != null) err.accept(r.error != null ? r.error : "SUBMIT_FAILED");
                return;
            }
            if (ok != null) ok.accept(r);
        });
    }

    /**
     * Variante bloccante: usa la coda background per invii istantanei.
     * Da NON chiamare dal render thread.
     */
    public static SubmitResult submitBlocking(PlotInfo info) {
        String base = getNormalizedEndpoint();
        if (base.isBlank()) {
            return errorResult("ENDPOINT_MISSING");
        }
        String url = deriveSubmitPlotUrl(base);

        AppConfig cfg = ConfigManager.get();
        String publishCode = cfg != null ? cfg.sessionCode : null;

        if (publishCode == null || publishCode.isBlank()) {
            return errorResult("SESSION_CODE_MISSING");
        }

        JsonObject plot = new JsonObject();
        plot.addProperty("plot_id", info.plotId);
        plot.addProperty("coord_x", info.coordX);
        plot.addProperty("coord_z", info.coordZ);
        if (info.dimension != null && !info.dimension.isBlank()) {
            plot.addProperty("dimension", info.dimension);
        } else if (cfg != null && cfg.dimensionDefault != null && !cfg.dimensionDefault.isBlank()) {
            plot.addProperty("dimension", cfg.dimensionDefault);
        }
        if (info.proprietario != null) plot.addProperty("proprietario", info.proprietario);
        if (info.ultimoAccessoIso != null) plot.addProperty("ultimo_accesso", info.ultimoAccessoIso);

        String authToken = resolveAuthToken(cfg);
        if (authToken == null || authToken.isBlank()) {
            return errorResult("TOKEN_MISSING");
        }

        JsonObject body = new JsonObject();
        body.addProperty("publish_code", publishCode);
        addOperatorInfo(body);
        body.add("plot_data", plot);

        SubmitResult result = postJsonWithRetryBlocking(url, body, authToken, SubmitResult.class, MAX_RETRIES);
        if (result == null) {
            return errorResult("NETWORK_ERROR");
        }
        return result;
    }

    // ====== HTTP core ======

    private static <T> void postJson(String url, JsonObject body, Class<T> cls, Consumer<T> cb) {
        postJsonWithRetry(url, body, null, cls, cb, 1);
    }

    private static String getNormalizedEndpoint() {
        AppConfig cfg = ConfigManager.get();
        if (cfg == null) return "";
        return normalizeUrl(cfg.endpointUrl);
    }

    static String resolveAuthToken(AppConfig cfg) {
        if (cfg != null && cfg.bearerToken != null && !cfg.bearerToken.isBlank()) {
            return cfg.bearerToken;
        }
        return buildOperatorToken();
    }

    private static void addOperatorInfo(JsonObject body) {
        body.addProperty("operator_name", getOperatorName());
        String uuid = getOperatorUuid();
        if (uuid != null && !uuid.isBlank()) {
            body.addProperty("operator_uuid", uuid);
        }
    }

    private static String buildOperatorToken() {
        String operatorName = getOperatorName();
        if (operatorName == null || operatorName.isBlank() || "unknown".equalsIgnoreCase(operatorName)) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("operator_name", operatorName);
        String uuid = getOperatorUuid();
        if (uuid != null && !uuid.isBlank()) {
            payload.addProperty("operator_uuid", uuid);
        }
        payload.addProperty("expires_at", System.currentTimeMillis() + TOKEN_TTL_MS);
        String json = payload.toString();
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean looksLikeOperatorToken(String token) {
        String raw = token == null ? "" : token.trim();
        if (raw.isBlank()) {
            return false;
        }
        if (raw.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            raw = raw.substring("Bearer ".length()).trim();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            String json = new String(decoded, StandardCharsets.UTF_8);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) {
                return false;
            }
            return obj.has("operator_name") && obj.has("expires_at");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static <T> void postJsonWithRetry(
            String url,
            JsonObject body,
            String bearerToken,
            Class<T> cls,
            Consumer<T> cb
    ) {
        postJsonWithRetry(url, body, bearerToken, cls, cb, MAX_RETRIES);
    }

    private static <T> T postJsonWithRetryBlocking(
            String url,
            JsonObject body,
            String bearerToken,
            Class<T> cls,
            int maxAttempts
    ) {
        try {
            HttpResponse<String> resp = null;
            String text = "";
            int status = 0;
            for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(REQ_TIMEOUT)
                        .header("Content-Type", "application/json");
                String authHeader = normalizeBearerToken(bearerToken);
                if (authHeader != null) builder.header("Authorization", authHeader);
                HttpRequest req = builder
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                status = resp.statusCode();
                text = resp.body() == null ? "" : resp.body();

                if (shouldRetry(status) && attempt < maxAttempts) {
                    sleepForRetry(attempt);
                    continue;
                }
                break;
            }

            T obj = null;
            try {
                obj = GSON.fromJson(text, cls);
            } catch (Exception parse) {
                obj = null;
            }

            if (obj == null) {
                obj = createErrorResponse(cls, status);
            }

            setHttpStatusIfPresent(obj, status);
            return obj;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> void postJsonWithRetry(
            String url,
            JsonObject body,
            String bearerToken,
            Class<T> cls,
            Consumer<T> cb,
            int maxAttempts
    ) {
        // thread separato (non blocca render thread)
        Thread worker = new Thread(() -> {
            try {
                HttpResponse<String> resp = null;
                String text = "";
                int status = 0;
                for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(REQ_TIMEOUT)
                            .header("Content-Type", "application/json");
                    String authHeader = normalizeBearerToken(bearerToken);
                    if (authHeader != null) builder.header("Authorization", authHeader);
                    HttpRequest req = builder
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build();

                    resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    status = resp.statusCode();
                    text = resp.body() == null ? "" : resp.body();

                    if (shouldRetry(status) && attempt < maxAttempts) {
                        sleepForRetry(attempt);
                        continue;
                    }
                    break;
                }

                T obj = null;
                try {
                    obj = GSON.fromJson(text, cls);
                } catch (Exception parse) {
                    // se parsing fallisce, ritorno null
                    obj = null;
                }

                if (obj == null) {
                    obj = createErrorResponse(cls, status);
                }

                // se è una delle nostre classi con httpStatus, setto via reflection safe
                setHttpStatusIfPresent(obj, status);

                if (cb != null) {
                    final T result = obj;
                    dispatchToMainThread(() -> cb.accept(result));
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (cb != null) {
                    dispatchToMainThread(() -> cb.accept(null));
                }
            } catch (Exception e) {
                if (cb != null) {
                    dispatchToMainThread(() -> cb.accept(null));
                }
            }
        }, "SMD-HTTP");
        worker.setDaemon(true);
        worker.start();
    }

    private static void setHttpStatusIfPresent(Object obj, int status) {
        if (obj == null) return;
        try {
            var f = obj.getClass().getDeclaredField("httpStatus");
            f.setAccessible(true);
            f.setInt(obj, status);
        } catch (Exception ignore) {
        }
    }

    private static SubmitResult errorResult(String error) {
        SubmitResult r = new SubmitResult();
        r.success = false;
        r.error = error;
        return r;
    }

    @SuppressWarnings("unchecked")
    private static <T> T createErrorResponse(Class<T> cls, int status) {
        String error = status > 0 ? "HTTP_" + status : "INVALID_RESPONSE";
        if (cls == SubmitResult.class) {
            SubmitResult r = new SubmitResult();
            r.success = false;
            r.error = error;
            r.httpStatus = status;
            return (T) r;
        }
        if (cls == SearchResult.class) {
            SearchResult r = new SearchResult();
            r.success = false;
            r.error = error;
            r.httpStatus = status;
            return (T) r;
        }
        if (cls == AuthResult.class) {
            AuthResult r = new AuthResult();
            r.authorized = false;
            r.reason = error;
            return (T) r;
        }
        if (cls == WhitelistRequestResult.class) {
            WhitelistRequestResult r = new WhitelistRequestResult();
            r.success = false;
            r.error = error;
            r.httpStatus = status;
            return (T) r;
        }
        return null;
    }

    private static String normalizeBearerToken(String bearerToken) {
        if (bearerToken == null) return null;
        String token = bearerToken.trim();
        if (token.isBlank()) return null;
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return token;
        }
        return "Bearer " + token;
    }

    private static boolean shouldRetry(int status) {
        return status == 429 || status >= 500;
    }

    private static void sleepForRetry(int attempt) throws InterruptedException {
        long delay = RETRY_BASE_DELAY_MS * (1L << Math.max(0, attempt - 1));
        Thread.sleep(delay);
    }

    // ====== Small UX helpers (opzionale) ======
    public static void chatInfo(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
    }

    private static void dispatchToMainThread(Runnable task) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.execute(task);
        } else {
            task.run();
        }
    }
}
