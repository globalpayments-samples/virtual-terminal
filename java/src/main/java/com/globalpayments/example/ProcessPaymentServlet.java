package com.globalpayments.example;

import com.global.api.ServicesContainer;
import com.global.api.entities.Address;
import com.global.api.entities.StoredCredential;
import com.global.api.entities.Transaction;
import com.global.api.entities.enums.AddressType;
import com.global.api.entities.enums.Channel;
import com.global.api.entities.enums.Environment;
import com.global.api.entities.enums.ManualEntryMethod;
import com.global.api.entities.enums.StoredCredentialInitiator;
import com.global.api.entities.enums.StoredCredentialSequence;
import com.global.api.entities.enums.StoredCredentialType;
import com.global.api.entities.exceptions.ApiException;
import com.global.api.entities.exceptions.ConfigurationException;
import com.global.api.paymentMethods.CreditCardData;
import com.global.api.serviceConfigs.GpApiConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MOTO (Mail Order / Telephone Order) Payment Processing Servlet
 *
 * Demonstrates GP-API (Unified Payments) MOTO transactions: an agent keys a card in directly
 * (no hosted fields/tokenization), the card is flagged with entry_mode = MOTO which exempts the
 * charge from SCA/3DS2 under PSD2. Optionally stores the card as a Credential on File (COF) for
 * later merchant-initiated transactions.
 *
 * Endpoints:
 * - GET /config: Returns basic environment info for the virtual terminal page
 * - POST /process-payment: Runs a MOTO charge, optionally storing the card (COF)
 */
@WebServlet(urlPatterns = {"/process-payment", "/config"})
public class ProcessPaymentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    // ignoreIfMissing: containers pass credentials as real env vars (--env-file), not a physical
    // .env file, and Dotenv.get() falls back to System.getenv() when no file is present.
    private final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    @Override
    public void init() throws ServletException {
        try {
            GpApiConfig config = new GpApiConfig();
            config.setAppId(cleanEnv(dotenv.get("GP_API_APP_ID")));
            config.setAppKey(cleanEnv(dotenv.get("GP_API_APP_KEY")));
            config.setCountry(cleanEnv(dotenv.get("GP_API_COUNTRY", "IE")));
            config.setChannel(Channel.CardNotPresent);
            config.setEnvironment(Environment.TEST); // sandbox
            // Never enable the SDK request logger here - it dumps full requests, PAN included.

            ServicesContainer.configureService(config);
        } catch (ConfigurationException e) {
            throw new ServletException("Failed to configure GP-API SDK", e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!"/config".equals(request.getServletPath())) {
            return;
        }
        response.setContentType("application/json");
        JsonObject data = new JsonObject();
        data.addProperty("environment", "sandbox");
        data.addProperty("merchantName", "GP MOTO Demo");
        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.add("data", data);
        response.getWriter().write(body.toString());
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setContentType("application/json");

        JsonObject req;
        try {
            String rawBody = request.getReader().lines().collect(Collectors.joining());
            req = JsonParser.parseString(rawBody).getAsJsonObject();
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
            return;
        }

        try {
            String cardNumber = requireString(req, "card_number");
            String expMonth = requireString(req, "exp_month");
            String expYear = requireString(req, "exp_year");
            String cvv = requireString(req, "cvv");
            String amountStr = requireString(req, "amount");

            BigDecimal amount;
            try {
                amount = new BigDecimal(amountStr);
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Amount must be a positive number");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid amount format");
            }

            String currency = optString(req, "currency", "EUR");
            String cardholderName = optString(req, "cardholder_name", null);
            boolean storeCard = req.has("store_card") && req.get("store_card").getAsBoolean();

            // Card is keyed in directly by the agent - entryMethod MOTO is what exempts this
            // charge from SCA/3DS2 under PSD2. Never omit it.
            CreditCardData card = new CreditCardData();
            card.setNumber(cardNumber);
            card.setExpMonth(Integer.parseInt(expMonth.trim()));
            card.setExpYear(Integer.parseInt(expYear.trim()));
            card.setCvn(cvv);
            card.setCardHolderName(cardholderName);
            card.setEntryMethod(ManualEntryMethod.Moto);

            String orderId = UUID.randomUUID().toString();

            var builder = card.charge(amount)
                    .withCurrency(currency)
                    .withClientTransactionId(orderId);

            if (req.has("billing_address") && req.get("billing_address").isJsonObject()) {
                JsonObject b = req.getAsJsonObject("billing_address");
                Address billingAddress = new Address();
                billingAddress.setStreetAddress1(optString(b, "line1", null));
                billingAddress.setCity(optString(b, "city", null));
                billingAddress.setState(optString(b, "state", null));
                billingAddress.setPostalCode(optString(b, "postal_code", null));
                billingAddress.setCountryCode(optString(b, "country", null));
                builder = builder.withAddress(billingAddress, AddressType.Billing);
            }

            if (storeCard) {
                // Credential on File: this is the initial customer-initiated transaction, so
                // sequence is First. The brand_reference returned below is what a merchant would
                // persist to run later merchant-initiated (MIT) MOTO charges against this card.
                StoredCredential storedCredential = new StoredCredential();
                storedCredential.setInitiator(StoredCredentialInitiator.CardHolder);
                storedCredential.setType(StoredCredentialType.Unscheduled);
                storedCredential.setSequence(StoredCredentialSequence.First);
                builder = builder.withStoredCredential(storedCredential);
            }

            Transaction txn = builder.execute();

            JsonObject data = new JsonObject();
            data.addProperty("transactionId", txn.getTransactionId());
            data.addProperty("status", txn.getResponseMessage());
            data.addProperty("cardLast4", last4(txn.getCardLast4()));
            data.addProperty("brand", txn.getCardType());
            data.addProperty("authCode", txn.getAuthorizationCode());
            if (txn.getCardBrandTransactionId() != null) {
                data.addProperty("brandReference", txn.getCardBrandTransactionId());
            }
            data.addProperty("amount", amountStr);
            data.addProperty("currency", currency);
            data.addProperty("reference", orderId);

            JsonObject body = new JsonObject();
            body.addProperty("success", true);
            body.add("data", data);
            response.getWriter().write(body.toString());

        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (ApiException e) {
            // Never log the PAN - log only the SDK-provided message.
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Strips a trailing " #comment" (e.g. "#gitleaks:allow") and surrounding whitespace from an
     * env value. `docker run --env-file` (unlike a .env parser) does not strip inline comments,
     * so they'd otherwise end up as part of the credential.
     */
    private static String cleanEnv(String value) {
        if (value == null) {
            return null;
        }
        int hashIdx = value.indexOf('#');
        return (hashIdx >= 0 ? value.substring(0, hashIdx) : value).trim();
    }

    /** Extracts the last 4 characters of a (possibly masked) card number string. */
    private static String last4(String value) {
        if (value == null || value.length() < 4) {
            return value;
        }
        return value.substring(value.length() - 4);
    }

    private static String requireString(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        String value = obj.get(field).getAsString();
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private static String optString(JsonObject obj, String field, String fallback) {
        JsonElement el = obj.get(field);
        return (el == null || el.isJsonNull()) ? fallback : el.getAsString();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        JsonObject body = new JsonObject();
        body.addProperty("success", false);
        body.addProperty("error", message);
        response.getWriter().write(body.toString());
    }
}
