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
import java.time.Duration;
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
        String base = normalizeUrl(ConfigManager.get().endpointUrl);
        if (base.isBlank()) {
            if (cb != null) cb.accept(null);
            return;
        }
        String url = deriveCheckAccessUrl(base);

        JsonObject body = new JsonObject();
        body.addProperty("operator_name", getOperatorName());
        String uuid = getOperatorUuid();
        if (uuid != null && !uuid.isBlank()) body.addProperty("operator_uuid", uuid);
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
        String base = normalizeUrl(ConfigManager.get().endpointUrl);
        if (base.isBlank()) {
            if (cb != null) cb.accept(null);
            return;
        }
        String url = deriveWhitelistRequestUrl(base);

        JsonObject body = new JsonObject();
        body.addProperty("operator_name", getOperatorName());
        String uuid = getOperatorUuid();
        if (uuid != null && !uuid.isBlank()) body.addProperty("operator_uuid", uuid);

        postJson(url, body, WhitelistRequestResult.class, cb);
    }

    public static void searchPlotAsync(String nome, Consumer<SearchResult> cb) {
        String base = normalizeUrl(ConfigManager.get().endpointUrl);
        if (base.isBlank()) {
            if (cb != null) cb.accept(null);
            return;
        }
        String url = deriveSearchPlotUrl(base);

        AppConfig cfg = ConfigManager.get();
        String publishCode = cfg != null ? cfg.sessionCode : null;

        JsonObject body = new JsonObject();
        body.addProperty("operator_name", getOperatorName());
        String uuid = getOperatorUuid();
        if (uuid != null && !uuid.isBlank()) body.addProperty("operator_uuid", uuid);

        body.addProperty("search_query", nome);
        if (publishCode != null && !publishCode.isBlank()) body.addProperty("publish_code", publishCode);

        postJson(url, body, SearchResult.class, cb);
    }

    public static void submitAsync(PlotInfo info, Consumer<SubmitResult> ok, Consumer<String> err) {
        String base = normalizeUrl(ConfigManager.get().endpointUrl);
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
        if (info.proprietario != null) plot.addProperty("proprietario", info.proprietario);
        if (info.ultimoAccessoIso != null) plot.addProperty("ultimo_accesso", info.ultimoAccessoIso);

        JsonObject body = new JsonObject();
        body.addProperty("publish_code", publishCode);
        body.addProperty("operator_name", getOperatorName());
        String uuid = getOperatorUuid();
        if (uuid != null && !uuid.isBlank()) body.addProperty("operator_uuid", uuid);
        body.add("plot_data", plot);

        postJson(url, body, SubmitResult.class, r -> {
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

    // ====== HTTP core ======

    private static <T> void postJson(String url, JsonObject body, Class<T> cls, Consumer<T> cb) {
        // thread separato (non blocca render thread)
        new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(REQ_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                String text = resp.body() == null ? "" : resp.body();

                T obj = null;
                try {
                    obj = GSON.fromJson(text, cls);
                } catch (Exception parse) {
                    // se parsing fallisce, ritorno null
                    obj = null;
                }

                // se è una delle nostre classi con httpStatus, setto via reflection safe
                setHttpStatusIfPresent(obj, status);

                if (cb != null) cb.accept(obj);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (cb != null) cb.accept(null);
            } catch (Exception e) {
                if (cb != null) cb.accept(null);
            }
        }, "SMD-HTTP").start();
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

    // ====== Small UX helpers (opzionale) ======
    public static void chatInfo(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
    }
}
