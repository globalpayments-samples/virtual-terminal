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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * MOTO (Mail Order / Telephone Order) Payment Processing Servlet
 *
 * Demonstrates GP-API (Unified Payments) MOTO transactions: an agent keys a card into Global
 * Payments hosted fields (secure iframes) which tokenize it in the browser, and the charge is
 * flagged with entry_mode = MOTO which exempts it from SCA/3DS2 under PSD2. The PAN never reaches
 * this server. Optionally stores the card as a Credential on File (COF) for later
 * merchant-initiated transactions.
 *
 * Endpoints:
 * - GET  /config: Returns basic environment info for the virtual terminal page
 * - POST /get-access-token: Mints a short-lived GP-API token for the browser's hosted fields
 * - POST /process-payment: Charges the tokenized card as MOTO, optionally storing it (COF)
 */
@WebServlet(urlPatterns = {"/process-payment", "/config", "/get-access-token"})
public class ProcessPaymentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    // GP-API access-token endpoint (used to mint the short-lived token the hosted fields need).
    private static final String ACCESS_TOKEN_URL = "https://apis.sandbox.globalpay.com/ucp/accesstoken";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final SecureRandom RANDOM = new SecureRandom();
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

        // The hosted fields ask for a short-lived access token before they can tokenize a card.
        if ("/get-access-token".equals(request.getServletPath())) {
            handleAccessToken(response);
            return;
        }

        JsonObject req;
        try {
            String rawBody = request.getReader().lines().collect(Collectors.joining());
            req = JsonParser.parseString(rawBody).getAsJsonObject();
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
            return;
        }

        try {
            String paymentReference = requireString(req, "payment_reference");
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
            boolean storeCard = req.has("store_card") && req.get("store_card").getAsBoolean();

            // The hosted fields already captured and tokenized the card; charge the single-use
            // token. entryMethod MOTO is what exempts this charge from SCA/3DS2 under PSD2 —
            // never omit it.
            CreditCardData card = new CreditCardData();
            card.setToken(paymentReference);
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
     * Mints a short-lived GP-API access token for the browser's hosted fields. The token is
     * scoped to a single tokenization (PMT_POST_Create_Single) so the page can turn the keyed
     * card into a single-use token without exposing the app credentials. The hash is
     * sha512(nonce + appKey), exactly as the GP-API access-token contract requires.
     */
    private void handleAccessToken(HttpServletResponse response) throws IOException {
        try {
            String appId = cleanEnv(dotenv.get("GP_API_APP_ID"));
            String appKey = cleanEnv(dotenv.get("GP_API_APP_KEY"));

            byte[] nonceBytes = new byte[16];
            RANDOM.nextBytes(nonceBytes);
            String nonce = toHex(nonceBytes);
            String secret = toHex(MessageDigest.getInstance("SHA-512")
                    .digest((nonce + appKey).getBytes(StandardCharsets.UTF_8)));

            JsonObject tokenRequest = new JsonObject();
            tokenRequest.addProperty("app_id", appId);
            tokenRequest.addProperty("nonce", nonce);
            tokenRequest.addProperty("secret", secret);
            tokenRequest.addProperty("grant_type", "client_credentials");
            tokenRequest.addProperty("seconds_to_expire", 600);
            JsonArray permissions = new JsonArray();
            permissions.add("PMT_POST_Create_Single");
            tokenRequest.add("permissions", permissions);

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ACCESS_TOKEN_URL))
                    .header("Content-Type", "application/json")
                    .header("X-GP-Version", "2021-03-22")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenRequest.toString()))
                    .build();

            HttpResponse<byte[]> httpResponse = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            JsonObject data = JsonParser.parseString(decodeBody(httpResponse)).getAsJsonObject();

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300
                    || !data.has("token") || data.get("token").isJsonNull()) {
                String message = data.has("error_description")
                        ? data.get("error_description").getAsString()
                        : "Failed to generate access token";
                writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message);
                return;
            }

            JsonObject body = new JsonObject();
            body.addProperty("success", true);
            body.addProperty("token", data.get("token").getAsString());
            body.addProperty("expiresIn",
                    data.has("seconds_to_expire") ? data.get("seconds_to_expire").getAsInt() : 600);
            response.getWriter().write(body.toString());
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to generate access token");
        }
    }

    /**
     * Reads the response body as UTF-8, transparently gunzipping it when GP-API returns the
     * payload gzip-compressed (java.net.http does not decompress automatically).
     */
    private static String decodeBody(HttpResponse<byte[]> resp) throws IOException {
        byte[] bytes = resp.body();
        String encoding = resp.headers().firstValue("content-encoding").orElse("");
        if (encoding.toLowerCase().contains("gzip")) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = gz.readAllBytes();
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Lowercase hex encoding, as the GP-API access-token nonce and secret are transmitted. */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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
