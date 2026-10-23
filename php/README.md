# PHP MOTO Payments Example (GP-API)

This example demonstrates **MOTO (Mail Order / Telephone Order)** card payments using
plain PHP and the Global Payments **GP-API (Unified Payments)** SDK. An agent keys a
customer's card into Global Payments hosted fields (secure iframes served by
`globalpayments.js`), which tokenize the card in the browser; the PHP backend then charges
the resulting single-use token with `entryMethod = MOTO`, which exempts the transaction from
SCA / 3D Secure 2 under PSD2. The PAN never reaches the server.

## Requirements

- PHP 8.2.35+, 8.3.36+, 8.4.27+, or 8.5.12+
- Composer
- Global Payments GP-API sandbox app credentials

## Project Structure

- `router.php` - Routes `/config`, `/get-access-token` and `/process-payment` to their
  scripts for `php -S`; everything else (`index.html`, `gp-checkout.css`) is served as a
  static file
- `process-payment.php` - GP-API config and the MOTO payment endpoint
- `get-access-token.php` - Mints the short-lived GP-API access token the hosted fields need
- `config.php` - Environment metadata endpoint
- `index.html` - Agent-facing MOTO virtual terminal form
- `gp-checkout.css` - Global Payments design-system styles
- `composer.json` - Project dependencies
- `.env.sample` - Template for environment variables
- `run.sh` - Convenience script to install and run

## Setup

1. Copy `.env.sample` to `.env`
2. Update `.env` with your GP-API credentials (sandbox values are pre-filled):
   ```
   GP_API_APP_ID=your_app_id
   GP_API_APP_KEY=your_app_key
   GP_API_COUNTRY=IE
   ```
3. Install dependencies:
   ```bash
   composer install
   ```
4. Run the application:
   ```bash
   ./run.sh
   ```
   Or manually:
   ```bash
   ./run.sh
   ```
   Then open http://localhost:8000.

## The two flows

Both go through the single `/process-payment` endpoint:

1. **Standard MOTO CIT** — a one-off charge. The charge is built from the single-use token
   (`$card->token = payment_reference`) with `entryMethod = ManualEntryMethod::MOTO` always
   set; no card storage.
2. **CIT with Credential on File (COF)** — the same charge plus a `StoredCredential` block
   (initiator = `PAYER`, type = `UNSCHEDULED`, sequence = `FIRST`). Tick "Store card" to send
   it. The response's `brandReference` is what you would persist to run future
   merchant-initiated transactions (MITs) against the same card.

## SDK Configuration

`GpApiConfig` is configured from environment variables: `appId`, `appKey`, `country`,
`channel = Channel::CardNotPresent`. The SDK defaults to the sandbox (`Environment::TEST`)
environment. The hosted fields need a short-lived access token, minted by
`POST /get-access-token` (scoped to a single tokenization, `PMT_POST_Create_Single`);
`GET /config` returns only the environment and merchant name.

## API Endpoints

### GET /config

```json
{ "success": true, "data": { "environment": "sandbox", "merchantName": "GP MOTO Demo" } }
```

### POST /get-access-token

No request body. Mints the short-lived GP-API access token for the hosted fields.

Response:

```json
{ "success": true, "token": "<access token>", "expiresIn": 600 }
```

### POST /process-payment

Request (JSON):

```json
{
  "payment_reference": "<single-use token from the hosted fields>",
  "amount": "49.99",
  "currency": "EUR",
  "billing_address": {
    "line1": "Apartment 852", "city": "Chicago", "state": "IL",
    "postal_code": "50001", "country": "840"
  },
  "store_card": true
}
```

`payment_reference` and `amount` are required; a missing field
returns `400 { "success": false, "error": "..." }`.

Response (200):

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

Declines and SDK errors return `500 { "success": false, "error": "..." }`. The PAN is never
logged or returned in an error.

## Test Card

Sandbox test card: **4263970000005262**, any future expiry, CVV **852**, keyed into the
hosted fields → expect `CAPTURED`.

## Security Considerations

The card is tokenized client-side in the hosted fields, so the PAN never reaches the server
(reduced PCI scope). This is sample code. For production also consider input validation, rate
limiting, security headers, structured logging (never the PAN), fraud checks, HTTPS, and CORS
configuration.
