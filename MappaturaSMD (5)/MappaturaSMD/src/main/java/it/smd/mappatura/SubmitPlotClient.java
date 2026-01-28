package it.smd.mappatura;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Client HTTP per API Mappatura SMD
 * FIX: rimosse classi unnamed / preview, compatibile Java 17
 */
public class SubmitPlotClient {

    public static class AuthResult {
        public boolean authorized;
        public String reason;
        public JsonObject debug;
    }

    public static class WhitelistRequestResult {
        public String status;
        public String error;
        public int httpStatus;
    }

    private static String base() {
        return ConfigManager.get().endpointUrl;
    }

    private static String playerName() {
        return MinecraftClient.getInstance().getSession().getUsername();
    }

    private static String playerUUID() {
        UUID u = MinecraftClient.getInstance().getSession().getUuidOrNull();
        return u != null ? u.toString().replace("-", "") : null;
    }

    /* ================= CHECK ACCESS ================= */

    public static void checkAccessAsync(Consumer<AuthResult> cb) {
        String url = base() + "/functions/checkAccess";
        JsonObject body = new JsonObject();
        body.addProperty("operator_name", playerName());
        body.addProperty("operator_uuid", playerUUID());
        post(url, body, cb, AuthResult.class);
    }

    /* ================= WHITELIST REQUEST ================= */

    public static void requestWhitelistAsync(Consumer<WhitelistRequestResult> cb) {
        String url = base() + "/functions/whitelistRequest";
        JsonObject body = new JsonObject();
        body.addProperty("operator_name", playerName());
        body.addProperty("operator_uuid", playerUUID());
        post(url, body, cb, WhitelistRequestResult.class);
    }

    /* ================= HTTP CORE ================= */

    private static <T> void post(String url, JsonObject body, Consumer<T> cb, Class<T> cls) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");

                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                T obj = new Gson().fromJson(txt, cls);

                if (obj instanceof WhitelistRequestResult r) {
                    r.httpStatus = code;
                }

                cb.accept(obj);
            } catch (Exception e) {
                e.printStackTrace();
                cb.accept(null);
            }
        }, "SMD-HTTP").start();
    }
}
