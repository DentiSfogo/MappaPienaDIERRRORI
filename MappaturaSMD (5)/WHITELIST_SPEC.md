# Specifica Whitelist (corretta e allineata al comportamento della mod)

Questa specifica allinea la documentazione al comportamento reale della mod presente in questo repo.

## 1) Entità chiave del backend

- **SmdWhitelist**: fonte di verità finale. Contiene gli operatori autorizzati.
  - Campi importanti:
    - `operator_name` (sempre **normalizzato in minuscolo** dal backend)
    - `operator_uuid` (**senza trattini**, opzionale ma consigliato)
    - `enabled` (boolean, `true` se l'operatore è attivo)
- **WhitelistRequest**: gestisce le richieste degli operatori per essere aggiunti alla whitelist.
  - Campi importanti:
    - `operator_name`
    - `operator_uuid`
    - `status` (`pending`, `approved`, `rejected`)

> Nota: la mod **non** forza il minuscolo per `operator_name`; invia il nome esatto di Minecraft.
> La normalizzazione in minuscolo va quindi fatta dal backend.

## 2) Lato Mod Minecraft: cosa fa e cosa aspettarsi

La mod interagisce con due funzioni backend principali.

### A. Richiesta di Whitelist (operatori non ancora autorizzati)

- **Funzione backend**: `functions/whitelistRequest` (endpoint pubblico)
- **Quando usarla**: quando l'operatore prova a usare una funzione che richiede whitelist e non risulta autorizzato.
- **Dati inviati dalla mod (JSON POST body)**:

```json
{
  "operator_name": "NomeDellOperatoreMinecraft", // richiesto (es. "ZeroTeam")
  "operator_uuid": "12345678123412341234123456789012" // opzionale, **senza trattini**
}
```

- **Importante**:
  - La mod invia `operator_name` **così come appare in Minecraft** (non in minuscolo).
  - La mod invia `operator_uuid` **senza trattini**.
  - Il backend si occupa della normalizzazione.

**Risposte attese (whitelistRequest response)**:
- `200 OK` + `{ success: true, status: "PENDING" }` → richiesta inviata.
- `200 OK` + `{ success: true, status: "ALREADY_PENDING" }` → richiesta già in attesa.
- `200 OK` + `{ success: true, status: "ALREADY_WHITELISTED" }` → già whitelistato.
- `400 Bad Request` → dati mancanti/errati (es. `operator_name` assente).
- `429 Too Many Requests` → troppe richieste; la mod dovrebbe fare retry con cooldown (es. 1 minuto).
- `500 Internal Server Error` → errore lato server.

### B. Controllo accesso (operazioni sensibili: submitPlot/searchPlot)

- **Funzione backend**: `functions/checkAccess` (endpoint privato che chiama `functions/checkWhitelistInternal`)
- **Quando usarla**: prima di ogni operazione che richiede whitelist.
- **Dati inviati dalla mod (JSON POST body)**:

```json
{
  "operator_name": "NomeDellOperatoreMinecraft", // richiesto
  "operator_uuid": "12345678123412341234123456789012", // opzionale, **senza trattini**
  "publish_code": "CODICE_SESSIONE_PUBBLICA" // opzionale
}
```

- **Importante**:
  - La mod invia `operator_name` e `operator_uuid` esattamente come in Minecraft.
  - Il backend gestisce la normalizzazione (minuscolo, eventuali controlli, ecc.).

**Risposte attese (checkAccess response)**:
- `200 OK` + `{ authorized: true, reason: "OK", ... }` → l'operatore è in whitelist.
- `403 Forbidden` + `{ authorized: false, reason: "NOT_WHITELISTED", ... }` → non whitelistato o disabilitato.
- `400 Bad Request` → dati mancanti/errati.
- `500 Internal Server Error` → errore lato server.
