# Integrazione Mod Minecraft → Backend Base44

Questa guida riassume gli endpoint Base44 da utilizzare con la mod di mappatura.

**Base URL:** `https://mappatura-smd-8dad3f3c.base44.app/functions/`

> Nota: nel file di configurazione della mod è possibile usare anche il base URL senza `/functions/`.

---

## 1. Check Access (test connessione)

**Endpoint:** `POST /checkAccess`

**Body JSON:**
```json
{
  "operator_name": "NomeGiocatore",
  "operator_uuid": "uuid-senza-trattini",
  "publish_code": "SMD-ZR-20260128-XXXXX"
}
```

**Response OK (200):**
```json
{
  "authorized": true,
  "reason": "OK",
  "session_id": "...",
  "session_name": "..."
}
```

**Response ERROR:**
```json
{
  "authorized": false,
  "reason": "NOT_WHITELISTED" | "INVALID_CODE" | "SESSION_NOT_ACTIVE"
}
```

---

## 2. Submit Plot (invio dati)

**Endpoint:** `POST /submitPlot`

**Body JSON:**
```json
{
  "publish_code": "SMD-ZR-20260128-XXXXX",
  "operator_name": "NomeGiocatore",
  "operator_uuid": "uuid-senza-trattini",
  "plot_data": {
    "plot_id": "-5;10",
    "coord_x": 123,
    "coord_z": 456,
    "dimension": "overworld",
    "proprietario": "PlayerName",
    "ultimo_accesso": "2026-01-28T10:00:00"
  }
}
```

**Response OK (200):**
```json
{
  "success": true,
  "alreadyMapped": false,
  "plot_key": "session_id:plot_id",
  "session_id": "...",
  "timestamp": "2026-01-28T10:00:00"
}
```

**Response ERROR (403 NOT_WHITELISTED):**
```json
{
  "success": false,
  "error": "NOT_WHITELISTED"
}
```

**Response ERROR (404 sessione non trovata):**
```json
{
  "success": false,
  "error": "Sessione non trovata o non attiva"
}
```

---

## Note importanti

- **UUID format:** rimuovere i trattini dall’UUID Minecraft.
  - Esempio: `48bb4fd3-eec1-4ec7-83a2-1b6f6d9f54eb` → `48bb4fd3eec14ec783a21b6f6d9f54eb`
- **Autenticazione:** nessun bearer token richiesto.
- **Deduplica:** il backend gestisce i duplicati con `plot_key = session_id:plot_id`.
- **Whitelist:** verificare sempre l’accesso prima di inviare dati.

---

## Flusso consigliato

**Salvataggio configurazione mod**
1. Chiamare `checkAccess` con i dati dell’utente.
2. Se `authorized: true` → configurazione OK.
3. Se `authorized: false` → mostrare errore.

**Durante la mappatura**
1. Inviare `submitPlot` per ogni plot visitato.
2. Se `success: true` → ok.
3. Se `success: false` → loggare l’errore.
