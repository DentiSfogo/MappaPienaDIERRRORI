# Mappatura SMD (Fabric 1.21.8) — GUI + HUD

## Build
Richiede Java 21.

```bash
gradle build
```

Jar in `build/libs/`.

## Uso in game
- **O** (configurabile): apre il pannello "Mappatura SMD"
- **M** (configurabile): Start/Stop rapido mappatura

### Nel pannello
1) Inserisci **Codice sessione**
2) Premi **Salva**
3) Premi **Avvia**

La mod salva tutto in `config/mappaturasmd.json` automaticamente. Il token segreto per `submitPlot` sta nel config (chiave `ingestKey` o `bearerToken`) e non viene richiesto nella GUI.

Nota: La verifica “sessione attiva” avviene al primo invio: se il codice è errato/non attivo vedrai HUD `Sessione non attiva o codice errato`.
