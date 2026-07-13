# MOTO Payments — GP-API Sample

A Global Payments sample project demonstrating **MOTO (Mail Order / Telephone Order)** payments
via **GP-API (Unified Payments)** using the official server-side SDKs. An agent-facing "virtual
terminal" page posts card details keyed in by an agent to a small backend, which charges the card
with `entry_mode = MOTO` — exempting it from Strong Customer Authentication (SCA) / 3D Secure 2
under PSD2 — and can optionally store the credential (Credential on File) for later
merchant-initiated transactions.

See the official docs: [GP-API MOTO](https://developer.globalpayments.com/docs/moto).

## Available Implementations

- [.NET Core](./dotnet/) - ASP.NET Core web application
- [Java](./java/) - Jakarta EE servlet-based web application
- [Node.js](./nodejs/) - Express.js web application
- [PHP](./php/) - PHP web application

Each implementation is functionally identical: same endpoints, same request/response contract,
same virtual terminal UI — just written idiomatically per language.

## The two flows

Both flows go through the single `POST /process-payment` endpoint.

1. **Standard MOTO CIT** — a one-off, customer-initiated charge. No card storage. The card is
   built with `entryMethod = MOTO`, which is what tells GP-API the card details were keyed in by
   an agent rather than typed by the cardholder, exempting the transaction from SCA/3DS2.
2. **CIT with Credential on File (COF)** — the same charge, plus a `StoredCredential` block
   (`initiator = CardHolder`, `type = Unscheduled`, `sequence = First`) attached to the request.
   The response's `brand_reference` must be surfaced to the caller — it's the token you'd persist
   and reuse on a later merchant-initiated transaction (MIT), quoting `sequence = Subsequent`.
   Toggle this flow with the "Store card for future merchant-initiated payments" checkbox on the
   virtual terminal.

## Endpoint contract

All four servers listen on `PORT` (default `8000`), bind `0.0.0.0`, and serve this same contract.
The full, language-agnostic contract (SDK configuration, MOTO/COF specifics, frontend states)
lives in [`docs/API-CONTRACT.md`](./docs/API-CONTRACT.md).

### `GET /config`

```json
{ "success": true, "data": { "environment": "sandbox", "merchantName": "GP MOTO Demo" } }
```

No public key is returned — MOTO is server-side keyed entry, there are no hosted fields to
initialize client-side.

### `POST /process-payment`

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
declines, SDK exceptions) return `{ "success": false, "error": "<message>" }` — never the raw PAN.

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

- Card number: `4263970000005262`
- Expiry: any future date
- CVV: `852`

Expect a `CAPTURED` response in sandbox.

## PCI note

This sample accepts and handles raw card numbers server-side — that's expected for MOTO, where
the card is agent-keyed rather than tokenized client-side, so there are no hosted fields to
offload PAN handling to. In production, any service that touches raw PANs is in PCI DSS scope:
plan for a PCI-compliant environment (network segmentation, encryption at rest/in transit,
restricted logging — this sample never logs full card numbers) rather than copying this pattern
directly into an unscoped environment.

## License

See [LICENSE](./LICENSE).
