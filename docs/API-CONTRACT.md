# MOTO Virtual Terminal — GP-API · API Contract

The detailed, language-agnostic contract that all four implementations in this repo
(`nodejs/`, `dotnet/`, `php/`, `java/`) implement identically. The root
[`README.md`](../README.md) is the entry point; this document is the reference for the
exact request/response shapes and the GP-API hosted-fields / MOTO / COF specifics behind them.

## Overview

A Global Payments sample demonstrating an agent-facing **MOTO (Mail Order / Telephone Order)**
virtual terminal on **GP-API (Unified Payments)**. The agent keys the card into Global Payments
**hosted fields** (secure iframes from `globalpayments.js`), which tokenize it in the browser; the
backend charges the resulting single-use token with `entry_mode = MOTO` (exempting it from
SCA/3DS2), optionally storing the credential (COF) for future merchant-initiated transactions. The
raw PAN never reaches the server.

Three endpoints:
1. `GET /config` — environment metadata for the page.
2. `POST /get-access-token` — mints the short-lived GP-API token the hosted fields need.
3. `POST /process-payment` — charges the tokenized card. Two flows:
   - **Standard MOTO CIT** — one-off charge, no card storage.
   - **CIT with Credential on File** — same charge plus a `StoredCredential` block
     (initiator=CardHolder/Payer, type=Unscheduled, sequence=First); the response's
     `brandReference` is surfaced so it can be persisted for later MITs.

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

Environment metadata only. The access token the hosted fields need is minted by
`POST /get-access-token`.

### `POST /get-access-token`

No request body. Mints a short-lived GP-API access token, scoped to a single tokenization, and
returns it for `globalpayments.js` to initialize the hosted fields.

Server behavior:
- Generate a random `nonce` (16 random bytes, lowercase hex).
- Compute `secret = sha512(nonce + appKey)` (lowercase hex).
- `POST` to `https://apis.sandbox.globalpay.com/ucp/accesstoken` with header
  `X-GP-Version: 2021-03-22` and JSON body:
  ```json
  {
    "app_id": "<GP_API_APP_ID>",
    "nonce": "<nonce>",
    "secret": "<sha512(nonce + appKey)>",
    "grant_type": "client_credentials",
    "seconds_to_expire": 600,
    "permissions": ["PMT_POST_Create_Single"]
  }
  ```
- The scope is deliberately minimal: `PMT_POST_Create_Single` allows exactly one card
  tokenization and nothing else, so the token handed to the browser cannot move money.
- GP-API responds gzip-compressed; clients that don't decompress automatically
  (PHP curl, .NET `HttpClient`, Java `HttpClient`) must enable decompression.

Response 200:
```json
{ "success": true, "token": "<access token>", "expiresIn": 600 }
```
On failure (non-2xx or no token) → 500 with `{ "success": false, "error": "<message>" }`.

### `POST /process-payment` (JSON body)

Request:
```json
{
  "payment_reference": "<single-use token from the hosted fields>",
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
- Validate presence of `payment_reference` and `amount` → 400 with
  `{ "success": false, "error": "<message>" }` if missing/invalid.
- Build the card from the token: set `CreditCardData.token = payment_reference` and
  **entryMethod = MOTO** (`ManualEntryMethod.Moto` / `::MOTO`) — this is the whole point of the
  sample; never omit it. No PAN/expiry/CVV is handled server-side.
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
message/status: `CAPTURED` for an approved charge, `DECLINED` for a soft decline (sandbox decline
card) — a decline is still a successful gateway call, so it returns 200 with `success: true` and
the same shape as above.

Only an actual API/SDK exception (`ApiException`, network/auth failure) returns 500 with
`{ "success": false, "error": "<message>" }`. 400 is used for request validation failures (see
above). No card data is ever leaked in errors or logs.

### Style rules (all languages)

- Match each language's idiomatic style — this is sample code developers read. Comments explain
  the hosted-fields / MOTO / COF-specific lines, not language basics.
- No extra abstractions or DB. Shortest working implementation.
- SDK request loggers that would dump request bodies are disabled.
- Sandbox test data: card `4263970000005262`, any future expiry, cvv `852` → expect CAPTURED.

## Frontend contract (one page, deployed identically to all 4 language dirs)

- Files: `index.html` + `gp-checkout.css` in each language dir (self-contained, loads
  DM Sans/DM Mono itself). The page loads `https://js.globalpay.com/4.1.11/globalpayments.js`.
- Design system: the Global Payments design system (`gp-checkout.css` class inventory and the
  drop-in checkout template). Brand compliance: gradient only on header/primary button,
  raspberry only for errors, 150% body line-height, tertiary colors never dominant.
- Page: agent-facing **MOTO Virtual Terminal**.
  - On load: `POST /get-access-token`, then `GlobalPayments.configure({ accessToken, apiVersion,
    env: 'sandbox' })`, then render the hosted card fields with
    `GlobalPayments.creditCard.form('#credit-card-form', …)`.
  - Fields: amount, currency (EUR/USD/GBP select), billing address block, and a "Store card for
    future merchant-initiated payments (COF)" checkbox are ordinary inputs; the card number,
    expiry, CVV, cardholder name and the submit button are hosted-field iframes.
  - On the hosted form's `token-success`, take `paymentReference`, attach the amount / billing /
    COF values, and `fetch` JSON to `/process-payment`. `token-error` shows an inline alert.
  - States: loading spinner (`.gp-spinner`); result receipt (`.gp-receipt`, transaction id,
    status badge, masked card, auth code, and brand reference callout when present) styled
    success (`.gp-result-badge.success`, `.gp-badge-success`) when `status` is `CAPTURED` or
    decline (`.gp-result-badge.decline`, `.gp-badge-error`) for any other status (chiefly
    `DECLINED`); inline error alert (`.gp-alert-error`) for tokenization or request/API failures.
  - A short info alert explaining what MOTO is and why `entry_mode=MOTO` exempts SCA.
- Vanilla JS in the page, no build step, no frameworks.

## docker-compose / root files

Root `docker-compose.yml`, `README.md`, and root `index.html` landing page cover the four
languages, ports 8001–8004 (host) → 8000 (container). Each container listens on `8000`
internally; compose maps it to the host port.
