package it.smd.mappatura;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatPlotInfoParser {

    // Debug: stampa righe utili in console
    private static final boolean DEBUG = false;

    /**
     * In molti server l'output di /plot info cambia (prefix, lingua, formati).
     * Per evitare di dipendere da un header preciso, il controller chiama beginRequest()
     * subito dopo aver inviato il comando, e noi "collezioniamo" per qualche secondo.
     */
    private boolean collecting = false;
    private long startedAtMs = 0L;
    private long requestId = 0L;

    // PlotSquared plot id tipico: "-5;10"
    private static final Pattern PLOT_ID_SEMI = Pattern.compile("(-?\\d+)\\s*;\\s*(-?\\d+)");
    // Alcuni output usano "#123"
    private static final Pattern PLOT_ID_NUM = Pattern.compile("#\\s*(\\d+)");
    // Coordinate tipo "-80, 160"
    private static final Pattern POS_COMMA = Pattern.compile("(-?\\d+)\\s*,\\s*(-?\\d+)");
    // Coordinate tipo "x: -80 z: 160" (anche in mezzo a testo)
    private static final Pattern POS_XZ = Pattern.compile(
            "(?:^|\\b)x\\s*[:=]?\\s*(-?\\d+)\\b.*\\b(?:z)\\s*[:=]?\\s*(-?\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OWNER = Pattern.compile(
            "(?:proprietario|owner)\\s*[»:\\-]\\s*(.+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LAST = Pattern.compile(
            "(?:ultimo\\s+accesso|last\\s+(?:seen|login))\\s*[»:\\-]\\s*(.+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final MappingController controller;

    private String plotId;   // "-5;10" oppure "123"
    private Integer coordX;  // world X (best effort)
    private Integer coordZ;  // world Z (best effort)
    private Integer fallbackCoordX; // fallback da posizione player
    private Integer fallbackCoordZ; // fallback da posizione player
    private String owner;
    private String last;

    public ChatPlotInfoParser(MappingController controller) {
        this.controller = controller;
    }

    public boolean isCollecting() {
        return collecting;
    }

    /**
     * ✅ Metodo richiesto dal MappingController.
     * Chiamalo subito dopo aver inviato /plot info.
     */
    public void beginRequest() {
        beginRequest(0L, null, null);
    }

    /**
     * Variante con fallback coord (es. posizione player al momento del comando).
     */
    public void beginRequest(Integer fallbackX, Integer fallbackZ) {
        beginRequest(0L, fallbackX, fallbackZ);
    }

    public void beginRequest(long requestId, Integer fallbackX, Integer fallbackZ) {
        resetInternal();
        this.requestId = requestId;
        this.fallbackCoordX = fallbackX;
        this.fallbackCoordZ = fallbackZ;
        collecting = true;
        startedAtMs = System.currentTimeMillis();
        if (DEBUG) System.out.println("[SMD][PARSER] beginRequest()");
    }

    public void onChatLine(String raw) {
        if (raw == null) return;

        String s = raw.replaceAll("§.", "").trim();
        if (s.isEmpty()) return;

        if (DEBUG && collecting) {
            System.out.println("[SMD][CHAT] " + s);
        }

        if (!collecting) return;

        // 1) plotId: preferiamo "-5;10" se presente
        if (plotId == null) {
            Matcher mSemi = PLOT_ID_SEMI.matcher(s);
            if (mSemi.find()) {
                String px = mSemi.group(1).trim();
                String pz = mSemi.group(2).trim();
                plotId = px + ";" + pz;
                if (DEBUG) System.out.println("[SMD][PARSER] plotId(semi)=" + plotId);
            } else {
                Matcher mNum = PLOT_ID_NUM.matcher(s);
                if (mNum.find()) {
                    plotId = mNum.group(1).trim();
                    if (DEBUG) System.out.println("[SMD][PARSER] plotId(#)=" + plotId);
                }
            }
        }

        // 2) coordX/coordZ: best effort (dipende dal server)
        if (coordX == null || coordZ == null) {
            Matcher mpXZ = POS_XZ.matcher(s);
            if (mpXZ.find()) {
                coordX = safeParseInt(mpXZ.group(1));
                coordZ = safeParseInt(mpXZ.group(2));
                if (DEBUG) System.out.println("[SMD][PARSER] pos(xz) x=" + coordX + " z=" + coordZ);
            } else {
                Matcher mpC = POS_COMMA.matcher(s);
                if (mpC.find()) {
                    coordX = safeParseInt(mpC.group(1));
                    coordZ = safeParseInt(mpC.group(2));
                    if (DEBUG) System.out.println("[SMD][PARSER] pos(comma) x=" + coordX + " z=" + coordZ);
                }
            }
        }

        // 3) owner
        Matcher mo = OWNER.matcher(s);
        if (mo.find()) {
            owner = mo.group(1).trim();
            if (DEBUG) System.out.println("[SMD][PARSER] owner=" + owner);
        }

        // 4) last access
        Matcher ml = LAST.matcher(s);
        if (ml.find()) {
            last = ml.group(1).trim();
            if (DEBUG) System.out.println("[SMD][PARSER] last=" + last);
        }

        emitIfPossible(false);
    }

    public void tick(long timeoutMs) {
        if (!collecting) return;

        long now = System.currentTimeMillis();
        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        if (now - startedAtMs > effectiveTimeout) {
            if (DEBUG) System.out.println("[SMD][PARSER] TIMEOUT soft -> emit finale se possibile");
            emitIfPossible(true);
            resetInternal();
            controller.onPlotInfoTimeout();
        }
    }

    public void forceReset() {
        resetInternal();
    }

    private void emitIfPossible(boolean fromTimeout) {
        // Senza plotId o coords non possiamo pushare secondo spec (plot_id, coord_x, coord_z obbligatori)
        Integer useX = coordX != null ? coordX : fallbackCoordX;
        Integer useZ = coordZ != null ? coordZ : fallbackCoordZ;
        if (plotId != null && useX != null && useZ != null) {
            PlotInfo info = new PlotInfo(
                    plotId,
                    useX,
                    useZ,
                    "overworld",
                    owner,
                    toIso(last),
                    requestId
            );

            System.out.println("[SMD] EMIT plot_id=" + plotId + " coord=(" + useX + "," + useZ + ")"
                    + (fromTimeout ? " [fromTimeout]" : ""));

            controller.onPlotInfoReady(info);
            resetInternal();
        }
    }

    private void resetInternal() {
        collecting = false;
        plotId = null;
        coordX = null;
        coordZ = null;
        fallbackCoordX = null;
        fallbackCoordZ = null;
        owner = null;
        last = null;
        requestId = 0L;
    }

    private static Integer safeParseInt(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return null; }
    }

    private static String toIso(String v) {
        if (v == null || v.isBlank()) return null;

        // formato italiano comune: dd/MM/yyyy HH:mm
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ITALY);
            return LocalDateTime.parse(v.trim(), fmt)
                    .atZone(ZoneId.of("Europe/Zurich"))
                    .toInstant().toString();
        } catch (Exception ignored) {}

        // fallback: lascia la stringa com'è
        return v.trim();
    }
}
