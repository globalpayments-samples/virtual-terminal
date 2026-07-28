# Java MOTO Payment Example (GP-API)

This example demonstrates **Mail Order / Telephone Order (MOTO)** payment processing using
Jakarta EE and the Global Payments GP-API (Unified Payments) SDK. An agent keys a customer's
card details into Global Payments hosted fields (secure iframes served by `globalpayments.js`),
which tokenize the card in the browser. The server then charges the resulting single-use token
with `entry_mode = MOTO`, which exempts the transaction from Strong Customer Authentication
(SCA/3DS2) under PSD2. The PAN never reaches the server.

## Two flows

1. **Standard MOTO CIT** — a one-off charge, no card storage.
2. **CIT with Credential on File (COF)** — same charge, plus a `StoredCredential` block
   (`initiator=CardHolder`, `type=Unscheduled`, `sequence=First`). The response's
   `brandReference` is returned so it could be persisted and used later for
   merchant-initiated transactions (MITs).

Both flows go through the single `POST /process-payment` endpoint, toggled by the
`store_card` flag in the request body.

## Requirements

- Java 21+ (image uses Java 21; SDK targets Java 23 source/target)
- Maven
- Global Payments GP-API sandbox credentials

## Project Structure

- `src/main/java/com/globalpayments/example/ProcessPaymentServlet.java` - config + hosted-fields access token (`POST /get-access-token`) + payment servlet
- `src/main/webapp/index.html` - MOTO virtual terminal form
- `src/main/webapp/WEB-INF/web.xml` - Web application configuration
- `.env.sample` - Template for environment variables
- `pom.xml` - Project dependencies and build configuration
- `run.sh` - Convenience script to run the application

## Setup

1. Clone this repository
2. Copy `.env.sample` to `.env`
3. Update `.env` with your GP-API sandbox credentials:
   ```
   GP_API_APP_ID=xxx
   GP_API_APP_KEY=xxx
   GP_API_COUNTRY=IE
   ```
4. Install dependencies:
   ```bash
   mvn clean install
   ```
5. Run the application:
   ```bash
   ./run.sh
   ```
   Or manually:
   ```bash
   mvn cargo:run
   ```

## Implementation Details

### SDK Configuration
GP-API configuration is handled in the servlet's `init` method:
- Loads `GP_API_APP_ID` / `GP_API_APP_KEY` / `GP_API_COUNTRY` from `.env`
- `Channel.CardNotPresent` (card-not-present / MOTO channel)
- Sandbox environment (`Environment.TEST`)
- Request logging is never enabled — the SDK's request logger would dump full requests,
  PAN included
- `POST /get-access-token` mints a short-lived GP-API access token, scoped to a single
  tokenization (`PMT_POST_Create_Single`), which the browser's hosted fields use

### Payment Processing
1. Client posts a JSON body with a `payment_reference` (single-use token from the hosted
   fields), amount, optional billing address and `store_card` flag
2. Server builds a `CreditCardData` with `card.setToken(paymentReference)` and
   `entryMethod = ManualEntryMethod.Moto` — this is the line that exempts the charge from SCA
3. A client transaction id (`UUID.randomUUID()`) is attached via `withClientTransactionId`
4. If a billing address is supplied, it's attached via `withAddress(address, AddressType.Billing)`
5. If `store_card` is true, a `StoredCredential` (CardHolder / Unscheduled / First) is attached
6. Charges the card and maps the SDK `Transaction` response to the JSON contract below

### Error Handling
- Missing/invalid required fields (`payment_reference`, `amount`) →
  `400` with `{ "success": false, "error": "..." }`
- SDK `ApiException` (declines, gateway errors) → `500` with the same shape
- The PAN is never logged or echoed back in error responses

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

Response (success):
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

Response (error):
```json
{ "success": false, "error": "<message>" }
```

## Test Card

Sandbox card `4263970000005262`, any future expiry, CVV `852` → expect `CAPTURED`. Keyed into
the hosted fields.

## Security Considerations

The card is tokenized client-side in the hosted fields, so the PAN never reaches the server
(reduced PCI scope).

This example demonstrates basic implementation. For production use, consider:
- Implementing additional input validation
- Adding request rate limiting
- Including security headers
- Implementing proper logging (never the PAN)
- Adding payment fraud prevention measures
- Configuring secure session management
