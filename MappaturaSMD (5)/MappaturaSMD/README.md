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

La mod salva tutto in `config/mappaturasmd.json` automaticamente. L'unico dato richiesto è il **codice sessione**: username e UUID vengono presi automaticamente dalla sessione di Minecraft, senza token Bearer o header Authorization.

Nota: La verifica “sessione attiva” avviene al primo invio: se il codice è errato/non attivo vedrai HUD `Sessione non attiva o codice errato`.

## Backend Base44 (integrazione rapida)
Base URL: `https://mappatura-smd-8dad3f3c.base44.app/functions/`

La mod accetta sia il base URL con `/functions/` sia quello senza suffisso: gli endpoint vengono normalizzati automaticamente.

### Endpoint principali
- `POST /checkAccess` → verifica whitelist operatore (consigliato al salvataggio config)
- `POST /submitPlot` → invio dati di mappatura

Nessun header di autenticazione richiesto: inviare solo `operator_name`, `operator_uuid` e `publish_code` nel body JSON. Per i dettagli completi vedi `INTEGRAZIONE_BASE44.md`.
