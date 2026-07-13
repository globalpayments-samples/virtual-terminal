# MOTO Payments — GP-API · API Contract

The detailed, language-agnostic contract that all four implementations in this repo
(`nodejs/`, `dotnet/`, `php/`, `java/`) implement identically. The root
[`README.md`](../README.md) is the entry point; this document is the reference for the
exact request/response shapes and the GP-API MOTO/COF specifics behind them.

## Overview

A Global Payments sample demonstrating **MOTO (Mail Order / Telephone Order)** payments via
**GP-API (Unified Payments)** using the official server-side SDKs. An agent-facing "virtual
terminal" page posts card details to a small backend, which charges the card with
`entry_mode = MOTO` (exempting it from SCA/3DS2), optionally storing the credential (COF) for
future merchant-initiated transactions.

Two flows, both via the single `/process-payment` endpoint:
1. **Standard MOTO CIT** — one-off charge, no card storage.
2. **CIT with Credential on File** — same charge plus a `StoredCredential` block
   (initiator=CardHolder/Payer, type=Unscheduled, sequence=First); the response's
   `brand_reference` is surfaced so it can be persisted for later MITs.

## Repository layout

Each language directory follows the `globalpayments-samples/starter-template` conventions per
language (`run.sh`, `.env.sample`, `Dockerfile`, `README.md`, idiomatic server file names). The
four directories are `nodejs/`, `dotnet/`, `php/`, `java/`, plus a shared frontend
(`index.html` + `gp-checkout.css`) deployed identically into each, a root landing
`index.html`, and a root `docker-compose.yml`.

## Backend contract (identical across all 4 languages)

Server listens on `PORT` env var, default **8000**, binding `0.0.0.0`. Serves its own
`index.html` + `gp-checkout.css` as static files.

### SDK configuration (GP-API)

- Config class: `GpApiConfig` (each SDK has it).
- `appId` = env `GP_API_APP_ID`, `appKey` = env `GP_API_APP_KEY`
- `channel` = CardNotPresent, `country` = env `GP_API_COUNTRY` default `IE`
- Sandbox environment (SDK default / explicit sandbox setting per SDK).
- `.env.sample` ships GP-API sandbox credentials (`GP_API_APP_ID`/`GP_API_APP_KEY`, marked
  `#gitleaks:allow`) plus `GP_API_COUNTRY=IE`. Copy it to `.env` before running.

### `GET /config`

```json
{ "success": true, "data": { "environment": "sandbox", "merchantName": "GP MOTO Demo" } }
```
(No public key — MOTO is server-side keyed entry; there are no hosted fields.)

### `POST /process-payment` (JSON body)

Request:
```json
{
  "card_number": "4263970000005262",
  "exp_month": "09",
  "exp_year": "2027",
  "cvv": "852",
  "cardholder_name": "James Mason",
  "amount": "49.99",
  "currency": "EUR",
  "billing_address": {
    "line1": "Apartment 852", "city": "Chicago", "state": "IL",
    "postal_code": "50001", "country": "840"
  },
  "store_card": false
}
```

Server behavior:
- Validate presence of card_number, exp_month, exp_year, cvv, amount → 400 with
  `{ "success": false, "error": "<message>" }` if missing/invalid.
- Build card object with **entryMethod = MOTO** (`ManualEntryMethod.Moto` / `::MOTO`) — this is
  the whole point of the sample; never omit it.
- Attach billing address (AddressType Billing) when provided.
- Generate a client transaction id (UUID/order id) and pass it via `withClientTransactionId`.
- If `store_card` is true: attach StoredCredential { initiator: CardHolder (PHP: PAYER),
  type: Unscheduled, sequence: First }.
- Charge (`card.charge(amount).withCurrency(...)...execute()`).

Response 200:
```json
{
  "success": true,
  "data": {
    "transactionId": "TRN_...",
    "status": "CAPTURED",
    "cardLast4": "5262",
    "brand": "VISA",
    "authCode": "12345",
    "brandReference": "L7KJKaFHfho2OKIo",
    "amount": "49.99",
    "currency": "EUR",
    "reference": "<client transaction id>"
  }
}
```
`brandReference` may be null/omitted when not returned; it is included whenever the SDK response
carries it (the response's card brand reference field). `status` comes from the SDK response
message/status (expect `CAPTURED` in sandbox).

Errors (declines, SDK ApiException) return 500 with
`{ "success": false, "error": "<message>" }`. The PAN is never leaked in errors or logs.

### Style rules (all languages)

- Match each language's idiomatic style — this is sample code developers read. Comments explain
  the MOTO/COF-specific lines, not language basics.
- No extra abstractions, endpoints, or DB. Shortest working implementation.
- Full card numbers are never logged; SDK request loggers that would dump the PAN are disabled.
- Sandbox test data: card `4263970000005262`, any future expiry, cvv `852` → expect CAPTURED.

## Frontend contract (one page, deployed identically to all 4 language dirs)

- Files: `index.html` + `gp-checkout.css` in each language dir (self-contained, loads
  DM Sans/DM Mono itself).
- Design system: the Global Payments design system (`gp-checkout.css` class inventory and the
  drop-in checkout template). Brand compliance: gradient only on header/primary button,
  raspberry only for errors, 150% body line-height, tertiary colors never dominant.
- Page: agent-facing **MOTO Virtual Terminal**. Plain HTML form (no hosted fields, no card
  tokenization JS — the agent keys the card in), posting JSON via fetch to `/process-payment`.
  - Fields: cardholder name, card number, expiry month/year, CVV, amount, currency (EUR/USD/GBP
    select), billing address block, and a "Store card for future merchant-initiated payments
    (COF)" checkbox.
  - States: loading spinner on submit (`.gp-spinner`), success receipt (`.gp-receipt`,
    transaction id, status badge, masked card, auth code, and brand reference callout when
    present), error alert (`.gp-alert-error`).
  - A short info alert explaining what MOTO is and why `entry_mode=MOTO` exempts SCA.
- Vanilla JS in the page, no build step, no frameworks.

## docker-compose / root files

Root `docker-compose.yml`, `README.md`, and root `index.html` landing page cover the four
languages, ports 8001–8004 (host) → 8000 (container). Each container listens on `8000`
internally; compose maps it to the host port.
