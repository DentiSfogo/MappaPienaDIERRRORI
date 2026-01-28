package it.smd.mappatura;

public class AppConfig {
    public String endpointUrl;

    // NON più richiesto all'operatore (key interna nel client). Tenuto solo per compatibilità.
    public String ingestKey;

    public String bearerToken;

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
        c.ingestKey = "SMD_INGEST_9f3a8c2e71b44d9abcf20e11a7d8c6b5"; // key interna
        c.bearerToken = "";
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
