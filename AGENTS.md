# MOTO Virtual Terminal — GP-API

> Charge an agent-keyed card as a MOTO transaction using GP-API hosted-fields tokenization,
> implemented identically in Node.js, .NET, PHP and Java.

## Critical Patterns

1. **The card is tokenized in the browser — the PAN never reaches the server.** The frontend
   loads `globalpayments.js`, mints an access token via `POST /get-access-token`, and renders
   hosted-field iframes. `/process-payment` receives only a single-use token
   (`payment_reference`), which becomes `CreditCardData.token`. There is no PAN/expiry/CVV
   handling in any backend — don't reintroduce it.

2. **`entryMethod = MOTO` is the whole point — never omit it.** Every backend sets
   `ManualEntryMethod.Moto` on the charge (Node `server.js:132`, .NET `Program.cs:191`, PHP
   `process-payment.php`, Java `ProcessPaymentServlet.java:144`). It flags the charge as
   agent-keyed so GP-API exempts it from SCA/3DS2 under PSD2. Dropping it changes the
   transaction's regulatory treatment.

3. **The access token is deliberately minimal-scope.** `/get-access-token` requests only
   `permissions: ["PMT_POST_Create_Single"]` — exactly one tokenization, nothing that can move
   money — using `secret = sha512(nonce + appKey)` against
   `https://apis.sandbox.globalpay.com/ucp/accesstoken` with `X-GP-Version: 2021-03-22`.

4. **GP-API responds gzip-compressed to the token mint.** Node `fetch` decompresses
   automatically; PHP curl needs `CURLOPT_ENCODING => ''`, .NET needs
   `AutomaticDecompression = All`, Java reads bytes and gunzips on `Content-Encoding: gzip`.
   Miss this and the token JSON parses as garbage.

5. **Credential on File (COF) is opt-in.** When `store_card` is true, attach a `StoredCredential`
   { initiator = CardHolder (PHP: PAYER), type = Unscheduled, sequence = First }. The response's
   `brandReference` is what a merchant persists to run later merchant-initiated (MIT) charges.

## Repository Structure

### Node.js (Express)
- [`nodejs/server.js`](nodejs/server.js) — all three endpoints; token mint (line ~81), token
  charge (line ~131). Canonical reference implementation.
- [`nodejs/index.html`](nodejs/index.html) — shared virtual-terminal frontend.

### .NET (ASP.NET Core minimal APIs)
- [`dotnet/Program.cs`](dotnet/Program.cs) — `ConfigureAccessTokenEndpoint` (line ~98),
  `/process-payment` (line ~157), token charge (line ~190).
- [`dotnet/wwwroot/index.html`](dotnet/wwwroot/index.html) — shared frontend.

### PHP (built-in server)
- [`php/get-access-token.php`](php/get-access-token.php) — token mint.
- [`php/process-payment.php`](php/process-payment.php) — token charge.
- [`php/config.php`](php/config.php) — `GET /config`. [`php/router.php`](php/router.php) maps
  clean paths to scripts.
- [`php/index.html`](php/index.html) — shared frontend.

### Java (Jakarta EE servlet)
- [`java/src/main/java/com/globalpayments/example/ProcessPaymentServlet.java`](java/src/main/java/com/globalpayments/example/ProcessPaymentServlet.java)
  — one servlet handling all three paths; `handleAccessToken` (line ~208), token charge
  (line ~143).
- [`java/src/main/webapp/index.html`](java/src/main/webapp/index.html) — shared frontend.

### Shared
- `index.html` (root) — landing page linking to the four servers.
- `gp-checkout.css` — Global Payments design system (deployed into each language dir).
- `docker-compose.yml` — all four, host ports 8001–8004 → container 8000.
- [`docs/API-CONTRACT.md`](docs/API-CONTRACT.md) — the exact language-agnostic contract.

## API Surface

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/config` | Environment metadata (`environment`, `merchantName`). |
| POST | `/get-access-token` | Mint a short-lived, single-tokenization GP-API access token for the hosted fields. |
| POST | `/process-payment` | Charge the tokenized card as MOTO; optional COF. |

## Environment Variables

```bash
GP_API_APP_ID=<GP-API sandbox app id>    # also used to sign the access-token nonce
GP_API_APP_KEY=<GP-API sandbox app key>  # secret = sha512(nonce + appKey)
GP_API_COUNTRY=IE                        # merchant country, default IE
```

`.env.sample` in each language dir ships working sandbox credentials (marked `#gitleaks:allow`).
Copy it to `.env` before running.

## Sandbox Test Card

Keyed into the hosted fields:

| Field | Value |
|-------|-------|
| Number | `4263970000005262` |
| Expiry | any future date |
| CVV | `852` |

Expect `status = CAPTURED`.

## Architecture Summary

**Tokenize:** page → `POST /get-access-token` → `globalpayments.js` hosted fields → single-use
`payment_reference` in the browser.
**Charge:** page → `POST /process-payment` (token + amount + billing + COF) → SDK
`card.charge()` with `entryMethod = MOTO` → receipt (`transactionId`, `status`, `brandReference`).

## Security Notes

Demo only. The PAN is tokenized client-side so it never reaches the server (reduced PCI scope),
but this sample has no auth, rate limiting, HTTPS termination, or persistence. Sandbox
credentials ship in `.env.sample` for convenience — never do that with live credentials. SDK
request loggers are left disabled so request bodies stay out of logs.

## SDK Versions

- Node.js: `globalpayments-api` v3.10+ (`nodejs/package.json`)
- .NET: `GlobalPayments.Api` v11.1+ (`dotnet/dotnet.csproj`)
- PHP: `globalpayments/php-sdk` v13.3+ (`php/composer.json`)
- Java: `globalpayments-sdk` v14.3+ (`java/pom.xml`)
