package it.smd.mappatura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SubmitQueueStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mappaturasmd_pending.json";

    private SubmitQueueStorage() {}

    public static synchronized List<PlotInfo> loadPending() {
        try {
            Path cfgDir = FabricLoader.getInstance().getConfigDir();
            Path file = cfgDir.resolve(FILE_NAME);
            if (!Files.exists(file)) return Collections.emptyList();
            String s = Files.readString(file, StandardCharsets.UTF_8);
            Persisted persisted = GSON.fromJson(s, Persisted.class);
            if (persisted == null || persisted.pending == null || persisted.pending.isEmpty()) {
                return Collections.emptyList();
            }
            List<PlotInfo> out = new ArrayList<>();
            for (PendingPlot pending : persisted.pending) {
                if (pending == null || pending.plotId == null || pending.plotId.isBlank()) continue;
                out.add(pending.toPlotInfo());
            }
            return out;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public static synchronized void savePending(List<PlotInfo> pending) {
        try {
            Path cfgDir = FabricLoader.getInstance().getConfigDir();
            Files.createDirectories(cfgDir);
            Persisted persisted = new Persisted();
            if (pending != null && !pending.isEmpty()) {
                persisted.pending = new ArrayList<>();
                for (PlotInfo info : pending) {
                    if (info != null) persisted.pending.add(PendingPlot.from(info));
                }
            }
            Path file = cfgDir.resolve(FILE_NAME);
            Files.writeString(file, GSON.toJson(persisted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static final class Persisted {
        private List<PendingPlot> pending = new ArrayList<>();
    }

    private static final class PendingPlot {
        private String plotId;
        private int coordX;
        private int coordZ;
        private String dimension;
        private String proprietario;
        private String ultimoAccessoIso;
        private long requestId;

        private static PendingPlot from(PlotInfo info) {
            PendingPlot pending = new PendingPlot();
            pending.plotId = info.plotId;
            pending.coordX = info.coordX;
            pending.coordZ = info.coordZ;
            pending.dimension = info.dimension;
            pending.proprietario = info.proprietario;
            pending.ultimoAccessoIso = info.ultimoAccessoIso;
            pending.requestId = info.requestId;
            return pending;
        }

        private PlotInfo toPlotInfo() {
            return new PlotInfo(
                    plotId,
                    coordX,
                    coordZ,
                    dimension,
                    proprietario,
                    ultimoAccessoIso,
                    requestId
            );
        }
    }
}
