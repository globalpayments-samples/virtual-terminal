/**
 * MOTO Payments — GP-API (Unified Payments) · Node.js
 *
 * Agent-facing "virtual terminal": keys a card into a small backend which charges it
 * with entry_mode = MOTO. Flagging a transaction as MOTO exempts it from SCA/3DS2 under
 * PSD2. Optionally stores the credential (COF) so the returned brand reference can be
 * persisted for future merchant-initiated transactions (MITs).
 */

import express from 'express';
import * as dotenv from 'dotenv';
import { randomUUID } from 'crypto';
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

// Malformed JSON bodies otherwise fall through to Express's default HTML error page;
// surface the same success:false envelope as every other error path instead.
app.use((err, req, res, next) => {
    if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
        return res.status(400).json({ success: false, error: 'Invalid JSON body' });
    }
    next(err);
});

/**
 * Config endpoint — MOTO is server-side keyed entry, so there is no public key or
 * hosted fields to expose to the browser.
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
 * Process a MOTO payment (standard CIT, or CIT + Credential on File when store_card is true).
 */
app.post('/process-payment', async (req, res) => {
    try {
        const {
            card_number, exp_month, exp_year, cvv,
            cardholder_name, amount, currency,
            billing_address, store_card
        } = req.body;

        // Validate required fields (never echo the PAN back).
        if (!card_number || !exp_month || !exp_year || !cvv || !amount) {
            return res.status(400).json({
                success: false,
                error: 'Missing required field(s): card_number, exp_month, exp_year, cvv, amount'
            });
        }

        const card = new CreditCardData();
        card.number = card_number;
        card.expMonth = exp_month;
        card.expYear = exp_year;
        card.cvn = cvv;
        card.cardHolderName = cardholder_name;
        card.entryMethod = ManualEntryMethod.MOTO; // the whole point — flags this as MOTO / SCA-exempt

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
        // Both SDK ApiError and generic errors surface only a message — never the PAN.
        const message = error instanceof ApiError ? error.message : (error.message || 'Payment processing failed');
        res.status(500).json({ success: false, error: message });
    }
});

app.listen(port, '0.0.0.0', () => {
    console.log(`MOTO virtual terminal running at http://localhost:${port}`);
});
