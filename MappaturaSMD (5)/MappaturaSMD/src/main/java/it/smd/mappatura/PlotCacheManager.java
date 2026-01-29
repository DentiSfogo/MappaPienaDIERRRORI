package it.smd.mappatura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Cache locale persistente (NON si svuota quando esci/rientri).
 * Salva su config/mappaturasmd_cache.json
 */
public class PlotCacheManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mappaturasmd_cache.json";
    private static final String UNKNOWN_OWNER = "Senza proprietario";

    /** key = owner normalizzato (lowercase), value = entries */
    private static final Map<String, List<Entry>> BY_OWNER = new HashMap<>();

    public static class Entry {
        public String owner;    // Nome proprietario (originale)
        public String plotId;   // "-5;10"
        public int coordX;
        public int coordZ;
        public long firstSeenAtMs;

        public Entry() {}

        public Entry(String owner, String plotId, int coordX, int coordZ) {
            this.owner = owner;
            this.plotId = plotId;
            this.coordX = coordX;
            this.coordZ = coordZ;
            this.firstSeenAtMs = System.currentTimeMillis();
        }

        public Entry(String owner, PlotInfo info) {
            this(owner, info.plotId, info.coordX, info.coordZ);
        }
    }

    private static class Persisted {
        public Map<String, List<Entry>> byOwner = new HashMap<>();
    }

    public static synchronized void init() {
        load();
    }

    /** Registra un plot (de-dup per plotId) */
    public static synchronized void record(PlotInfo info) {
        if (info == null) return;
        recordBasic(info.proprietario, info.plotId, info.coordX, info.coordZ);
    }

    /** Registra un plot minimale (usato anche per risultati remoti searchPlot) */
    public static synchronized void recordBasic(String owner, String plotId, int coordX, int coordZ) {
        if (plotId == null || plotId.isBlank()) return;

        String ownerOriginal = (owner == null || owner.isBlank()) ? UNKNOWN_OWNER : owner.trim();
        String key = ownerOriginal.toLowerCase(Locale.ROOT);

        List<Entry> list = BY_OWNER.computeIfAbsent(key, k -> new ArrayList<>());

        list.removeIf(Objects::isNull);
        for (Entry e : list) {
            if (e != null && Objects.equals(e.plotId, plotId)) return;
        }

        list.add(new Entry(ownerOriginal, plotId, coordX, coordZ));
        save();
    }

    /** Ritorna tutti i plot assegnati a un owner (cache locale) */
    public static synchronized List<Entry> search(String owner) {
        if (owner == null) return Collections.emptyList();
        String k = owner.trim().toLowerCase(Locale.ROOT);

        List<Entry> list = BY_OWNER.get(k);
        if (list == null) return Collections.emptyList();

        List<Entry> out = new ArrayList<>(list);
        out.sort(Comparator.comparing(a -> a.plotId));
        return out;
    }

    /** Per TAB / suggerimenti */
    public static synchronized List<String> getAllOwners() {
        List<String> out = new ArrayList<>();
        for (List<Entry> list : BY_OWNER.values()) {
            if (list != null && !list.isEmpty()) {
                list.removeIf(Objects::isNull);
                if (list.isEmpty()) {
                    continue;
                }
                String owner = list.get(0).owner;
                if (owner != null && !owner.isBlank()) out.add(owner);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Formatta SOLO: Nome + plotId + (x, z) */
    public static synchronized String formatForChat(String owner) {
        List<Entry> list = search(owner);

        StringBuilder sb = new StringBuilder();
        sb.append(owner).append("\n");

        if (list.isEmpty()) {
            sb.append("Nessun plot trovato.");
            return sb.toString();
        }

        for (Entry e : list) {
            sb.append(e.plotId)
              .append(" - (")
              .append(e.coordX).append(", ").append(e.coordZ)
              .append(")")
              .append("\n");
        }

        return sb.toString().trim();
    }

    /** Verifica se un plotId è già presente in cache (qualsiasi owner). */
    public static synchronized boolean isPlotMapped(String plotId) {
        if (plotId == null || plotId.isBlank()) return false;
        String target = plotId.trim();
        for (List<Entry> list : BY_OWNER.values()) {
            if (list == null) continue;
            for (Entry entry : list) {
                if (entry != null && target.equals(entry.plotId)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static synchronized void clear() {
        BY_OWNER.clear();
        save();
    }

    private static void load() {
        try {
            Path cfgDir = FabricLoader.getInstance().getConfigDir();
            Path file = cfgDir.resolve(FILE_NAME);
            if (!Files.exists(file)) return;

            String s = Files.readString(file, StandardCharsets.UTF_8);
            Persisted p = GSON.fromJson(s, Persisted.class);

            if (p != null && p.byOwner != null) {
                BY_OWNER.clear();
                BY_OWNER.putAll(p.byOwner);
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Path cfgDir = FabricLoader.getInstance().getConfigDir();
            Files.createDirectories(cfgDir);

            Persisted p = new Persisted();
            p.byOwner = BY_OWNER;

            Path file = cfgDir.resolve(FILE_NAME);
            Files.writeString(file, GSON.toJson(p), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
