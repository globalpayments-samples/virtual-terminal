# MOTO Virtual Terminal — GP-API Sample

A Global Payments sample project demonstrating an agent-facing **MOTO (Mail Order / Telephone
Order)** virtual terminal on **GP-API (Unified Payments)**. The agent keys the customer's card
into Global Payments **hosted fields** (secure iframes served by `globalpayments.js`), which
tokenize the card in the browser. The backend then charges the resulting single-use token with
`entry_mode = MOTO` — exempting it from Strong Customer Authentication (SCA) / 3D Secure 2 under
PSD2 — and can optionally store the credential (Credential on File) for later merchant-initiated
transactions.

Because the card is tokenized client-side, the raw card number (PAN) never reaches this
application — the whole point of using hosted fields.

See the official docs: [GP-API MOTO](https://developer.globalpayments.com/docs/moto) ·
[Hosted Fields](https://developer.globalpayments.com/docs/integration-options/hosted-fields).

## Available Implementations

| Language | Framework | SDK Package | Host port |
|----------|-----------|-------------|-----------|
| [**.NET**](./dotnet/) | ASP.NET Core | `GlobalPayments.Api` | 8001 |
| [**Node.js**](./nodejs/) | Express.js | `globalpayments-api` | 8002 |
| [**PHP**](./php/) | Built-in server | `globalpayments/php-sdk` | 8003 |
| [**Java**](./java/) | Jakarta EE servlet | `globalpayments-sdk` | 8004 |

Each implementation is functionally identical: same endpoints, same request/response contract,
the same virtual terminal UI — just written idiomatically per language.

## How it works

```
Agent keys card into hosted fields (iframes)
        │  globalpayments.js tokenizes in the browser
        ▼
  single-use token (payment_reference)  ──POST /process-payment──▶  backend
        ▲                                                              │
        │  access token (POST /get-access-token)                      │ charge token,
        └──────────────── minted server-side ◀────────────────────────┘ entry_mode = MOTO
```

1. On load, the page calls `POST /get-access-token`. The server mints a short-lived GP-API
   access token scoped to a single tokenization (`PMT_POST_Create_Single`) and returns it.
2. `globalpayments.js` uses that token to render the hosted card fields and, on submit,
   tokenizes the card into a single-use `payment_reference`.
3. The page posts that token (plus amount, billing address, and the COF flag) to
   `POST /process-payment`, which charges it as a MOTO transaction.

## The two flows

Both flows go through the single `POST /process-payment` endpoint.

1. **Standard MOTO CIT** — a one-off, customer-initiated charge. No card storage. The charge is
   built with `entryMethod = MOTO`, which tells GP-API the card details were keyed in by an agent
   rather than typed by the cardholder, exempting the transaction from SCA/3DS2.
2. **CIT with Credential on File (COF)** — the same charge, plus a `StoredCredential` block
   (`initiator = CardHolder`, `type = Unscheduled`, `sequence = First`) attached to the request.
   The response's `brandReference` must be surfaced to the caller — it's the token you'd persist
   and reuse on a later merchant-initiated transaction (MIT), quoting `sequence = Subsequent`.
   Toggle this flow with the "Store card for future merchant-initiated payments" checkbox on the
   virtual terminal.

## Endpoint contract

All four servers listen on `PORT` (default `8000`), bind `0.0.0.0`, and serve this same contract.
The full, language-agnostic contract (SDK configuration, hosted-fields flow, MOTO/COF specifics,
frontend states) lives in [`docs/API-CONTRACT.md`](./docs/API-CONTRACT.md).

### `GET /config`

```json
{ "success": true, "data": { "environment": "sandbox", "merchantName": "GP MOTO Demo" } }
```

Environment metadata for the page. The access token the hosted fields need is minted separately
by `POST /get-access-token`.

### `POST /get-access-token`

No request body. Mints a short-lived GP-API access token (scoped to `PMT_POST_Create_Single`) for
`globalpayments.js` to initialize the hosted fields.

```json
{ "success": true, "token": "<access token>", "expiresIn": 600 }
```

### `POST /process-payment`

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

`payment_reference` and `amount` are required; a missing field returns
`400 { "success": false, "error": "..." }`.

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

`brandReference` is present whenever the SDK response returns one (relevant for the COF flow).
Set `store_card: true` to attach the Stored Credential block. Errors (validation failures,
declines, SDK exceptions) return `{ "success": false, "error": "<message>" }`.

## Quick start

Per language:

```bash
cd nodejs        # or dotnet, php, java
cp .env.sample .env
# edit .env with your GP-API sandbox app id / app key
./run.sh
```

Then open `http://localhost:8000`.

## Running everything with Docker Compose

```bash
cp nodejs/.env.sample .env   # any language's sample has the same GP_API_* keys
docker compose up --build
```

| Language | Host port |
|----------|-----------|
| .NET     | 8001      |
| Node.js  | 8002      |
| PHP      | 8003      |
| Java     | 8004      |

Each container listens on `8000` internally; compose maps it to the host port above. Open the
root [`index.html`](./index.html) in a browser for links to all four.

## Sandbox test card

Keyed into the hosted fields:

- Card number: `4263970000005262`
- Expiry: any future date
- CVV: `852`

Expect a `CAPTURED` response in sandbox. More cards:
[GP-API test cards](https://developer.globalpayments.com/resources/test-cards).

## PCI note

By capturing the card in Global Payments hosted fields, the PAN is tokenized in the browser and
**never touches this application** — the server only ever sees a single-use token. This keeps the
merchant server out of the card-data environment and reduces PCI DSS scope (the hosted-fields
integration model, broadly SAQ A). In production, still serve the page over HTTPS, keep the
`globalpayments.js` script loaded directly from Global Payments (never self-hosted or proxied),
and never log tokens or transaction identifiers alongside customer data.

## License

See [LICENSE](./LICENSE).
