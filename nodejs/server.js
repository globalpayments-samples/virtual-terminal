/**
 * MOTO Payments — GP-API (Unified Payments) · Node.js
 *
 * Agent-facing "virtual terminal": the agent keys a card into Global Payments hosted fields
 * (secure iframes) in the browser, which tokenise it — the PAN never reaches this server. The
 * backend charges the resulting single-use token with entry_mode = MOTO. Flagging a transaction
 * as MOTO exempts it from SCA/3DS2 under PSD2. Optionally stores the credential (COF) so the
 * returned brand reference can be persisted for future merchant-initiated transactions (MITs).
 */

import express from 'express';
import * as dotenv from 'dotenv';
import { randomUUID, randomBytes, createHash } from 'crypto';
import {
    ServicesContainer,
    GpApiConfig,
    Environment,
    Channel,
    Address,
    AddressType,
    CreditCardData,
    ManualEntryMethod,
    StoredCredential,
    StoredCredentialInitiator,
    StoredCredentialType,
    StoredCredentialSequence,
    ApiError
} from 'globalpayments-api';

// Load environment variables from .env file
dotenv.config();

const app = express();
const port = process.env.PORT || 8000;

app.use(express.static('.')); // Serve static files (index.html + gp-checkout.css)
app.use(express.urlencoded({ extended: true }));
app.use(express.json());

// Configure the GP-API (Unified Payments) SDK for sandbox, card-not-present.
const config = new GpApiConfig();
config.appId = process.env.GP_API_APP_ID;
config.appKey = process.env.GP_API_APP_KEY;
config.country = process.env.GP_API_COUNTRY || 'IE';
config.channel = Channel.CardNotPresent;
config.environment = Environment.Test; // sandbox
ServicesContainer.configureService(config);

// GP-API access-token endpoint (used to mint the short-lived token the hosted fields need).
const ACCESS_TOKEN_URL = 'https://apis.sandbox.globalpay.com/ucp/accesstoken';

// Malformed JSON bodies otherwise fall through to Express's default HTML error page;
// surface the same success:false envelope as every other error path instead.
app.use((err, req, res, next) => {
    if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
        return res.status(400).json({ success: false, error: 'Invalid JSON body' });
    }
    next(err);
});

/**
 * Config endpoint — environment metadata for the virtual terminal page.
 */
app.get('/config', (req, res) => {
    res.json({
        success: true,
        data: {
            environment: 'sandbox',
            merchantName: 'GP MOTO Demo'
        }
    });
});

/**
 * Mint a short-lived GP-API access token for the browser's hosted fields.
 *
 * The token is scoped to a single tokenisation (PMT_POST_Create_Single) so the page can
 * turn the keyed card into a single-use token without exposing the app credentials. The
 * hash is sha512(nonce + appKey), exactly as the GP-API access-token contract requires.
 */
app.post('/get-access-token', async (req, res) => {
    try {
        const nonce = randomBytes(16).toString('hex');
        const secret = createHash('sha512').update(nonce + process.env.GP_API_APP_KEY).digest('hex');

        const response = await fetch(ACCESS_TOKEN_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-GP-Version': '2021-03-22' },
            body: JSON.stringify({
                app_id: process.env.GP_API_APP_ID,
                nonce,
                secret,
                grant_type: 'client_credentials',
                seconds_to_expire: 600,
                permissions: ['PMT_POST_Create_Single']
            })
        });

        const data = await response.json();
        if (!response.ok || !data.token) {
            throw new Error(data.error_description || 'Failed to generate access token');
        }

        res.json({ success: true, token: data.token, expiresIn: data.seconds_to_expire || 600 });
    } catch (error) {
        res.status(500).json({ success: false, error: error.message || 'Failed to generate access token' });
    }
});

/**
 * Process a MOTO payment (standard CIT, or CIT + Credential on File when store_card is true).
 *
 * The card arrives as a single-use token (payment_reference) from the hosted fields; the PAN
 * is never seen here.
 */
app.post('/process-payment', async (req, res) => {
    try {
        const { payment_reference, amount, currency, billing_address, store_card } = req.body;

        // Validate required fields.
        if (!payment_reference || !amount) {
            return res.status(400).json({
                success: false,
                error: 'Missing required field(s): payment_reference, amount'
            });
        }

        // The hosted fields already captured and tokenised the card; charge the token with
        // entry_mode = MOTO — the whole point of this sample (flags it as MOTO / SCA-exempt).
        const card = new CreditCardData();
        card.token = payment_reference;
        card.entryMethod = ManualEntryMethod.MOTO;

        // Build the charge; MOTO is CNP so a client transaction id ties it back to our order.
        const orderId = randomUUID();
        let builder = card
            .charge(Number(amount))
            .withCurrency(currency || 'EUR')
            .withClientTransactionId(orderId);

        // Billing address (AVS) when provided.
        if (billing_address) {
            const address = new Address();
            address.streetAddress1 = billing_address.line1;
            address.city = billing_address.city;
            address.state = billing_address.state;
            address.postalCode = billing_address.postal_code;
            address.countryCode = billing_address.country;
            builder = builder.withAddress(address, AddressType.Billing);
        }

        // Credential on File: mark this as the first storage of the card so the returned
        // brand reference can be persisted for later merchant-initiated MOTO charges.
        if (store_card) {
            const storedCredential = new StoredCredential();
            storedCredential.initiator = StoredCredentialInitiator.CardHolder;
            storedCredential.type = StoredCredentialType.UNSCHEDULED;
            storedCredential.sequence = StoredCredentialSequence.FIRST;
            builder = builder.withStoredCredentials(storedCredential);
        }

        const response = await builder.execute();

        res.json({
            success: true,
            data: {
                transactionId: response.transactionId,
                status: response.responseMessage,           // e.g. CAPTURED
                cardLast4: response.cardLast4 ? response.cardLast4.slice(-4) : null,
                brand: response.cardType,                    // e.g. VISA
                authCode: response.authorizationCode,
                brandReference: response.cardBrandTransactionId, // persist for future MITs
                amount,
                currency: currency || 'EUR',
                reference: orderId
            }
        });
    } catch (error) {
        // Both SDK ApiError and generic errors surface only a message — never card data.
        const message = error instanceof ApiError ? error.message : (error.message || 'Payment processing failed');
        res.status(500).json({ success: false, error: message });
    }
});

app.listen(port, '0.0.0.0', () => {
    console.log(`MOTO virtual terminal running at http://localhost:${port}`);
});
