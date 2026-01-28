package it.smd.mappatura;

public class AppConfig {
    public String endpointUrl;

    public String sessionCode;

    public boolean autoStart;

    public String plotInfoCommand;

    public int tickInterval;

    public long commandCooldownMs;

    public long parserTimeoutMs;

    public String dimensionDefault;

    // Stato accesso (salvato, così se riapri GUI vedi subito)
    public boolean authorized;
    public String lastAuthMessage;

    public static AppConfig defaults() {
        AppConfig c = new AppConfig();
        c.endpointUrl = "https://mappatura-smd-8dad3f3c.base44.app";
        c.sessionCode = "";
        c.autoStart = false;
        c.plotInfoCommand = "plot info";
        c.tickInterval = 1;
        c.commandCooldownMs = 600;
        c.parserTimeoutMs = 5000;
        c.dimensionDefault = "overworld";
        c.authorized = false;
        c.lastAuthMessage = "Non verificato";
        return c;
    }
}
