package it.smd.mappatura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mappaturasmd.json";
    private static AppConfig config;

    public static void init() {
        Path cfgDir = FabricLoader.getInstance().getConfigDir();
        Path file = cfgDir.resolve(FILE_NAME);

        if (!Files.exists(file)) {
            config = AppConfig.defaults();
            save();
            return;
        }
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            config = GSON.fromJson(s, AppConfig.class);
            if (config == null) config = AppConfig.defaults();
            // migrazioni/sicurezze
            if (config.endpointUrl == null || config.endpointUrl.isBlank()) config.endpointUrl = AppConfig.defaults().endpointUrl;
            config.endpointUrl = SubmitPlotClient.normalizeUrl(config.endpointUrl);
            if (config.plotInfoCommand == null || config.plotInfoCommand.isBlank()) config.plotInfoCommand = AppConfig.defaults().plotInfoCommand;
            if (config.tickInterval <= 0) config.tickInterval = AppConfig.defaults().tickInterval;
            if (config.commandCooldownMs < 0) config.commandCooldownMs = AppConfig.defaults().commandCooldownMs;
            if (config.parserTimeoutMs < 1000) config.parserTimeoutMs = AppConfig.defaults().parserTimeoutMs;
            if (config.dimensionDefault == null || config.dimensionDefault.isBlank()) config.dimensionDefault = AppConfig.defaults().dimensionDefault;
            if (config.bearerToken == null) config.bearerToken = AppConfig.defaults().bearerToken;
        } catch (Exception e) {
            config = AppConfig.defaults();
            save();
        }
    }

    public static AppConfig get() {
        if (config == null) init();
        return config;
    }

    public static void save() {
        try {
            Path cfgDir = FabricLoader.getInstance().getConfigDir();
            Path file = cfgDir.resolve(FILE_NAME);
            Files.createDirectories(cfgDir);
            Files.writeString(file, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
