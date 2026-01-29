package it.smd.mappatura;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * /mappatura cerca <nome>
 * /mappatura richiestawhitelist
 * /mappatura debug
 *
 * - Output in chat (non copia automaticamente)
 * - Ogni riga ha un bottone [COPIA] che copia "x z" nella clipboard
 *
 * Compatibile con Yarn 1.21.8+: ClickEvent è un'interfaccia, si usano i record annidati
 * (es. ClickEvent.CopyToClipboard / ClickEvent.OpenUrl).
 */
public class MappaturaCommands {

    private static final MutableText PREFIX =
            Text.literal("[SMD] ").formatted(Formatting.DARK_GRAY, Formatting.BOLD);

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerInternal(dispatcher));
    }

    private static void registerInternal(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("mappatura")
                .then(literal("cerca")
                        .then(argument("nome", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String nome = StringArgumentType.getString(ctx, "nome").trim();
                                    return cerca(nome);
                                })))
                .then(literal("richiestawhitelist").executes(ctx -> richiediWhitelist()))
                .then(literal("debug").executes(ctx -> debug()))
        );

        // comando diretto: /richiestawhitelist
        dispatcher.register(literal("richiestawhitelist").executes(ctx -> richiediWhitelist()));
    }

    private static int cerca(String nome) {
        if (nome.isBlank()) {
            send(Text.literal("Uso: ").formatted(Formatting.RED)
                    .append(Text.literal("/mappatura cerca <nome>").formatted(Formatting.YELLOW, Formatting.BOLD)));
            return 0;
        }

        send(Text.literal("🔍 Ricerca di ").formatted(Formatting.GRAY)
                .append(Text.literal(nome).formatted(Formatting.AQUA, Formatting.BOLD)));

        SubmitPlotClient.searchPlotAsync(nome, r -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) {
                return;
            }
            mc.execute(() -> {
                if (r == null) {
                    send(Text.literal("❌ Errore: risposta nulla").formatted(Formatting.RED, Formatting.BOLD));
                    return;
                }
                if (!r.success) {
                    String detail = buildErrorDetail(r.error, r.httpStatus, r.debug, null);
                    send(Text.literal("❌ Errore: ").formatted(Formatting.RED, Formatting.BOLD)
                            .append(Text.literal(detail).formatted(Formatting.RED)));
                    return;
                }

                JsonArray arr = r.results;
                if (arr == null || arr.isEmpty()) {
                    send(Text.literal("Nessun plot trovato.").formatted(Formatting.GRAY));
                    return;
                }

                send(Text.literal("📌 Risultati trovati: " + arr.size()).formatted(Formatting.GOLD, Formatting.BOLD));

                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();

                    String plot = safeString(o, "plot_id", "plot");
                    int x = safeInt(o, "coord_x", "x");
                    int z = safeInt(o, "coord_z", "z");

                    String coords = x + " " + z;

                    // Bottone copia (cliccabile)
                    MutableText copyBtn = Text.literal(" [COPIA]")
                            .formatted(Formatting.AQUA, Formatting.BOLD)
                            .styled(s -> s.withClickEvent(new ClickEvent.CopyToClipboard(coords)));

                    // (x, z) cliccabile anche lui (copiatura veloce)
                    MutableText coordsText = Text.literal(" (" + x + ", " + z + ")")
                            .formatted(Formatting.YELLOW)
                            .styled(s -> s.withClickEvent(new ClickEvent.CopyToClipboard(coords)));

                    MutableText row = Text.literal("• ").formatted(Formatting.DARK_GRAY)
                            .append(Text.literal(plot).formatted(Formatting.GREEN, Formatting.BOLD))
                            .append(coordsText)
                            .append(copyBtn);

                    send(row);
                }
            });
        });

        return 1;
    }

    private static int richiediWhitelist() {
        send(Text.literal("📨 Richiesta whitelist…").formatted(Formatting.GRAY));

        SubmitPlotClient.requestWhitelistAsync(r -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.execute(() -> showWhitelistResult(r));
            else showWhitelistResult(r);
        });

        return 1;
    }

    private static void showWhitelistResult(SubmitPlotClient.WhitelistRequestResult r) {
        if (r == null) {
            send(Text.literal("❌ Errore: risposta nulla").formatted(Formatting.RED, Formatting.BOLD));
            return;
        }

        String status = String.valueOf(r.status);

        switch (status) {
            case "ALREADY_WHITELISTED" -> send(Text.literal("✅ Sei già whitelistato.").formatted(Formatting.GREEN, Formatting.BOLD));
            case "ALREADY_PENDING" -> send(Text.literal("⏳ Hai già una richiesta in attesa.").formatted(Formatting.GOLD, Formatting.BOLD));
            case "PENDING" -> {
                send(Text.literal("✅ Richiesta inviata! ").formatted(Formatting.GREEN, Formatting.BOLD)
                        .append(Text.literal("In attesa di approvazione.").formatted(Formatting.GRAY)));

                String url = "https://mappaturasmd.com/operatori";
                MutableText open = Text.literal("Apri pannello whitelist")
                        .formatted(Formatting.AQUA, Formatting.UNDERLINE)
                        .styled(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
                send(Text.literal("🔗 ").formatted(Formatting.DARK_GRAY).append(open));
            }
            default -> {
                String err = buildErrorDetail(r.error, r.httpStatus, null, r.message);
                send(Text.literal("❌ Richiesta fallita: ").formatted(Formatting.RED, Formatting.BOLD)
                        .append(Text.literal(err).formatted(Formatting.RED)));
            }
        }
    }

    /**
     * /mappatura debug
     * Stampa in chat: endpoint, url effettive, sessionCode, username, uuid e fa un checkAccess live.
     */
    private static int debug() {
        AppConfig cfg = ConfigManager.get();

        String endpointRaw = (cfg.endpointUrl == null ? "" : cfg.endpointUrl);
        String endpointNorm = endpointRaw == null ? "" : endpointRaw.trim();
        if (!endpointNorm.isBlank()) endpointNorm = SubmitPlotClient.normalizeUrl(endpointNorm);

        String checkUrl = endpointNorm.isBlank() ? "(endpoint mancante)" : SubmitPlotClient.deriveCheckAccessUrl(endpointNorm);
        String searchUrl = endpointNorm.isBlank() ? "(endpoint mancante)" : SubmitPlotClient.deriveSearchPlotUrl(endpointNorm);
        String whitelistUrl = endpointNorm.isBlank() ? "(endpoint mancante)" : SubmitPlotClient.deriveWhitelistRequestUrl(endpointNorm);

        String session = (cfg.sessionCode == null ? "" : cfg.sessionCode.trim());
        String opName = SubmitPlotClient.getOperatorName();
        String opUuid = SubmitPlotClient.getOperatorUuid();

        send(Text.literal("🧪 DEBUG MOD").formatted(Formatting.GOLD, Formatting.BOLD));
        send(Text.literal("• endpointUrl: ").formatted(Formatting.GRAY)
                .append(Text.literal(endpointRaw.isBlank() ? "(vuoto)" : endpointRaw).formatted(Formatting.AQUA)));
        send(Text.literal("• endpointNorm: ").formatted(Formatting.GRAY)
                .append(Text.literal(endpointNorm.isBlank() ? "(vuoto)" : endpointNorm).formatted(Formatting.AQUA)));
        send(Text.literal("• checkAccess URL: ").formatted(Formatting.GRAY)
                .append(Text.literal(checkUrl).formatted(Formatting.DARK_AQUA)));
        send(Text.literal("• searchPlot URL: ").formatted(Formatting.GRAY)
                .append(Text.literal(searchUrl).formatted(Formatting.DARK_AQUA)));
        send(Text.literal("• whitelistRequest URL: ").formatted(Formatting.GRAY)
                .append(Text.literal(whitelistUrl).formatted(Formatting.DARK_AQUA)));
        send(Text.literal("• sessionCode: ").formatted(Formatting.GRAY)
                .append(Text.literal(session.isBlank() ? "(vuoto)" : session).formatted(Formatting.YELLOW)));
        send(Text.literal("• operator_name: ").formatted(Formatting.GRAY)
                .append(Text.literal(opName == null ? "(null)" : opName).formatted(Formatting.GREEN)));
        send(Text.literal("• operator_uuid: ").formatted(Formatting.GRAY)
                .append(Text.literal(opUuid == null ? "(null)" : opUuid).formatted(Formatting.GREEN)));

        // CheckAccess live
        send(Text.literal("⏳ checkAccess…").formatted(Formatting.GRAY));
        SubmitPlotClient.checkAccessAsync(session.isBlank() ? null : session, r -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) {
                return;
            }
            mc.execute(() -> {
                if (r == null) {
                    send(Text.literal("❌ checkAccess: risposta nulla").formatted(Formatting.RED, Formatting.BOLD));
                    return;
                }
                if (r.authorized) {
                    send(Text.literal("✅ checkAccess OK").formatted(Formatting.GREEN, Formatting.BOLD)
                            .append(Text.literal(" (" + (r.reason == null ? "OK" : r.reason) + ")").formatted(Formatting.GRAY)));
                } else {
                    String reason = (r.reason == null ? "NOT_AUTHORIZED" : r.reason);
                    String status = r.httpStatus > 0 ? " HTTP " + r.httpStatus : "";
                    send(Text.literal("❌ checkAccess NEGATO").formatted(Formatting.RED, Formatting.BOLD)
                            .append(Text.literal(" (" + reason + status + ")").formatted(Formatting.RED)));
                    if (r.debug != null && r.debug.has("exception")) {
                        send(Text.literal("• exception: ").formatted(Formatting.GRAY)
                                .append(Text.literal(r.debug.get("exception").getAsString()).formatted(Formatting.YELLOW)));
                    }
                }
            });
        });

        return 1;
    }

    private static void send(Text t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        MutableText msg = PREFIX.copy().append(t);

        if (mc.player != null) {
            mc.player.sendMessage(msg, false);
        } else if (mc.inGameHud != null && mc.inGameHud.getChatHud() != null) {
            mc.inGameHud.getChatHud().addMessage(msg);
        }
    }

    private static String safeString(JsonObject o, String key1, String key2) {
        if (o.has(key1) && !o.get(key1).isJsonNull()) return o.get(key1).getAsString();
        if (o.has(key2) && !o.get(key2).isJsonNull()) return o.get(key2).getAsString();
        return "plot";
    }

    private static int safeInt(JsonObject o, String key1, String key2) {
        if (o.has(key1) && !o.get(key1).isJsonNull()) return o.get(key1).getAsInt();
        if (o.has(key2) && !o.get(key2).isJsonNull()) return o.get(key2).getAsInt();
        return 0;
    }

    private static String buildErrorDetail(String error, int httpStatus, JsonObject debug, String message) {
        String detail = (error != null && !error.isBlank()) ? error : (httpStatus > 0 ? "HTTP " + httpStatus : "ERRORE");
        if (message != null && !message.isBlank() && !message.equalsIgnoreCase(detail)) {
            detail += " (" + message + ")";
        }
        if (debug != null && debug.has("exception")) {
            detail += " [" + debug.get("exception").getAsString() + "]";
        }
        return detail;
    }
}
