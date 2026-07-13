# .NET MOTO Payments Example (GP-API)

This example demonstrates **MOTO (Mail Order / Telephone Order)** card payments using
ASP.NET Core minimal APIs and the Global Payments **GP-API (Unified Payments)** SDK. An agent
keys a customer's card into a virtual terminal; the backend charges it with `entry_mode = MOTO`,
which exempts the transaction from SCA / 3D Secure 2 under PSD2.

## Requirements

- .NET 10.0 SDK or later
- Global Payments GP-API sandbox app credentials

## Project Structure

- `Program.cs` - Minimal API server, GP-API config, and the MOTO payment endpoint
- `wwwroot/index.html` - Agent-facing MOTO virtual terminal form
- `wwwroot/gp-checkout.css` - Global Payments design-system styles
- `dotnet.csproj` - Project dependencies
- `.env.sample` - Template for environment variables
- `run.sh` - Convenience script to restore and run
- `appsettings.json` - Application configuration file

## Setup

1. Copy `.env.sample` to `.env`
2. Update `.env` with your GP-API credentials (sandbox values are pre-filled):
   ```
   GP_API_APP_ID=your_app_id
   GP_API_APP_KEY=your_app_key
   GP_API_COUNTRY=IE
   ```
3. Run the application:
   ```bash
   ./run.sh
   ```
   Or manually: `dotnet restore && dotnet run`. Then open http://localhost:8000.

## The two flows

Both go through the single `/process-payment` endpoint:

1. **Standard MOTO CIT** — a one-off charge. `EntryMethod = ManualEntryMethod.Moto` is always
   set; no card storage.
2. **CIT with Credential on File (COF)** — the same charge plus a `StoredCredential` block
   (initiator = CardHolder, type = Unscheduled, sequence = First). Tick "Store card" to send
   it. The response's `brandReference` is what you would persist to run future
   merchant-initiated transactions (MITs) against the same card.

## SDK Configuration

`GpApiConfig` is configured from environment variables: `AppId`, `AppKey`, `Country`,
`Channel = Channel.CardNotPresent`, and the sandbox (`Environment.TEST`) environment. Because
MOTO is server-side keyed entry there are no hosted fields and no public key — `GET /config`
returns only the environment and merchant name. The SDK's request logger is never enabled, so
raw card numbers are never written to logs.

## API Endpoints

### GET /config

```json
{ "success": true, "data": { "environment": "sandbox", "merchantName": "GP MOTO Demo" } }
```

### POST /process-payment

Request (JSON):

```json
{
  "card_number": "4263970000005262",
  "exp_month": "09", "exp_year": "2028", "cvv": "852",
  "cardholder_name": "James Mason",
  "amount": "49.99", "currency": "EUR",
  "billing_address": {
    "line1": "Apartment 852", "city": "Chicago", "state": "IL",
    "postal_code": "50001", "country": "840"
  },
  "store_card": true
}
```

`card_number`, `exp_month`, `exp_year`, `cvv` and `amount` are required; a missing field
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

Sandbox test card: **4263970000005262**, any future expiry, CVV **852** → expect `CAPTURED`.

## Security Considerations

This is sample code. For production also consider input validation, rate limiting, security
headers, structured logging (never the PAN), fraud checks, HTTPS, and CORS configuration.
