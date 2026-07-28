using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GlobalPayments.Api;
using GlobalPayments.Api.Entities;
using GlobalPayments.Api.PaymentMethods;
using dotenv.net;

namespace MotoPaymentSample;

/// <summary>
/// MOTO (Mail Order / Telephone Order) Payment Sample
///
/// Demonstrates a virtual-terminal style flow: an agent keys a card into Global Payments
/// hosted fields (secure iframes) which tokenize it in the browser, and the server charges
/// the resulting single-use token via GP-API with entry_mode = MOTO, which exempts the
/// transaction from SCA/3DS2 under PSD2. Optionally stores the card as a Credential on File
/// (COF) for later merchant-initiated transactions.
/// </summary>
public class Program
{
    // GP-API responds gzip-compressed; enable automatic decompression so the body is plain JSON.
    private static readonly HttpClient Http = new(new HttpClientHandler
    {
        AutomaticDecompression = System.Net.DecompressionMethods.All
    });

    // GP-API access-token endpoint (used to mint the short-lived token the hosted fields need).
    private const string AccessTokenUrl = "https://apis.sandbox.globalpay.com/ucp/accesstoken";

    public static void Main(string[] args)
    {
        DotEnv.Load();

        var builder = WebApplication.CreateBuilder(args);
        var app = builder.Build();

        app.UseDefaultFiles();
        app.UseStaticFiles();

        ConfigureGlobalPaymentsSDK();
        ConfigureEndpoints(app);

        var port = System.Environment.GetEnvironmentVariable("PORT") ?? "8000";
        app.Urls.Add($"http://0.0.0.0:{port}");

        app.Run();
    }

    /// <summary>
    /// Configures the GP-API SDK for MOTO (card-not-present) transactions. The card reaches
    /// the server only as a single-use token from the hosted fields, so no raw PAN is handled
    /// here; no RequestLogger is set to keep request bodies out of the logs regardless.
    /// </summary>
    private static void ConfigureGlobalPaymentsSDK()
    {
        ServicesContainer.ConfigureService(new GpApiConfig
        {
            AppId = EnvVar("GP_API_APP_ID"),
            AppKey = EnvVar("GP_API_APP_KEY"),
            Country = EnvVar("GP_API_COUNTRY") ?? "IE",
            Channel = Channel.CardNotPresent,
            Environment = GlobalPayments.Api.Entities.Environment.TEST
        });
    }

    /// <summary>
    /// dotenv.net doesn't strip inline "# comment" suffixes (unlike other dotenv parsers), and
    /// .env.sample keeps gitleaks-allow comments on the credential lines, so trim them here.
    /// </summary>
    private static string? EnvVar(string name) =>
        System.Environment.GetEnvironmentVariable(name)?.Split('#')[0].Trim();

    private static void ConfigureEndpoints(WebApplication app)
    {
        app.MapGet("/config", () => Results.Ok(new
        {
            success = true,
            data = new
            {
                environment = "sandbox",
                merchantName = "GP MOTO Demo"
            }
        }));

        ConfigureAccessTokenEndpoint(app);
        ConfigurePaymentEndpoint(app);
    }

    /// <summary>
    /// Mints a short-lived GP-API access token for the browser's hosted fields. The token is
    /// scoped to a single tokenization (PMT_POST_Create_Single) so the page can turn the keyed
    /// card into a single-use token without exposing the app credentials. The hash is
    /// sha512(nonce + appKey), exactly as the GP-API access-token contract requires.
    /// </summary>
    private static void ConfigureAccessTokenEndpoint(WebApplication app)
    {
        app.MapPost("/get-access-token", async () =>
        {
            var appId = EnvVar("GP_API_APP_ID");
            var appKey = EnvVar("GP_API_APP_KEY");

            var nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            var secret = Convert.ToHexString(
                SHA512.HashData(Encoding.UTF8.GetBytes(nonce + appKey))).ToLowerInvariant();

            var tokenRequest = new
            {
                app_id = appId,
                nonce,
                secret,
                grant_type = "client_credentials",
                seconds_to_expire = 600,
                permissions = new[] { "PMT_POST_Create_Single" }
            };

            using var request = new HttpRequestMessage(HttpMethod.Post, AccessTokenUrl);
            request.Headers.Add("X-GP-Version", "2021-03-22");
            request.Content = new StringContent(
                JsonSerializer.Serialize(tokenRequest), Encoding.UTF8, "application/json");

            try
            {
                using var response = await Http.SendAsync(request);
                using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
                var root = doc.RootElement;

                if (!response.IsSuccessStatusCode ||
                    !root.TryGetProperty("token", out var tokenEl) ||
                    tokenEl.ValueKind != JsonValueKind.String)
                {
                    var error = root.TryGetProperty("error_description", out var e)
                        ? e.GetString() : "Failed to generate access token";
                    return Results.Json(new { success = false, error }, statusCode: 500);
                }

                var expiresIn = root.TryGetProperty("seconds_to_expire", out var s) &&
                    s.ValueKind == JsonValueKind.Number ? s.GetInt32() : 600;

                return Results.Ok(new { success = true, token = tokenEl.GetString(), expiresIn });
            }
            catch (Exception ex)
            {
                return Results.Json(new { success = false, error = ex.Message }, statusCode: 500);
            }
        });
    }

    /// <summary>
    /// Charges the tokenized card via GP-API MOTO. Body is snake_case JSON per the sample
    /// contract; the card arrives as a single-use token (payment_reference) from the hosted
    /// fields. The response is camelCase (ASP.NET Core minimal APIs default to the Web JSON
    /// naming policy, so plain camelCase anonymous-object properties round-trip correctly).
    /// </summary>
    private static void ConfigurePaymentEndpoint(WebApplication app)
    {
        app.MapPost("/process-payment", async (HttpContext context) =>
        {
            JsonDocument? doc = null;
            try
            {
                doc = await JsonDocument.ParseAsync(context.Request.Body);
            }
            catch (JsonException)
            {
                return Results.Json(new { success = false, error = "Invalid JSON body" }, statusCode: 400);
            }
            using var _ = doc;
            var root = doc.RootElement;

            string? Str(string name) =>
                root.TryGetProperty(name, out var v) && v.ValueKind != JsonValueKind.Null ? v.GetString() : null;

            var paymentReference = Str("payment_reference");
            var amountStr = Str("amount");
            var currency = Str("currency") ?? "EUR";

            if (string.IsNullOrEmpty(paymentReference) ||
                string.IsNullOrEmpty(amountStr) ||
                !decimal.TryParse(amountStr, out var amount) || amount <= 0)
            {
                return Results.Json(new { success = false, error = "Missing or invalid required fields" }, statusCode: 400);
            }

            // The hosted fields already captured and tokenized the card. entryMethod = MOTO is
            // the whole point of this sample: it flags the transaction as agent-keyed so GP-API
            // exempts it from SCA/3DS2.
            var card = new CreditCardData
            {
                Token = paymentReference,
                EntryMethod = ManualEntryMethod.Moto
            };

            var orderId = Guid.NewGuid().ToString();
            var chargeBuilder = card.Charge(amount)
                .WithCurrency(currency)
                .WithClientTransactionId(orderId);

            if (root.TryGetProperty("billing_address", out var addrEl) && addrEl.ValueKind == JsonValueKind.Object)
            {
                string? AddrStr(string name) =>
                    addrEl.TryGetProperty(name, out var v) && v.ValueKind != JsonValueKind.Null ? v.GetString() : null;

                var billingAddress = new Address
                {
                    StreetAddress1 = AddrStr("line1"),
                    City = AddrStr("city"),
                    State = AddrStr("state"),
                    PostalCode = AddrStr("postal_code"),
                    CountryCode = AddrStr("country")
                };
                chargeBuilder = chargeBuilder.WithAddress(billingAddress, AddressType.Billing);
            }

            if (root.TryGetProperty("store_card", out var storeEl) && storeEl.ValueKind == JsonValueKind.True)
            {
                // Credential on File: this initial CIT is the "First" in an Unscheduled series;
                // the returned brandReference is what a later MIT would reference.
                var storedCredential = new StoredCredential
                {
                    Initiator = StoredCredentialInitiator.CardHolder,
                    Type = StoredCredentialType.Unscheduled,
                    Sequence = StoredCredentialSequence.First
                };
                chargeBuilder = chargeBuilder.WithStoredCredential(storedCredential);
            }

            try
            {
                var response = chargeBuilder.Execute();

                return Results.Ok(new
                {
                    success = true,
                    data = new
                    {
                        transactionId = response.TransactionId,
                        status = response.ResponseMessage, // GP-API's top-level "status", e.g. CAPTURED
                        cardLast4 = Last4(response.CardDetails?.MaskedNumberLast4),
                        brand = response.CardType,
                        authCode = response.AuthorizationCode,
                        brandReference = response.CardBrandTransactionId,
                        amount = amountStr,
                        currency,
                        reference = orderId
                    }
                });
            }
            catch (ApiException ex)
            {
                // Never leak the PAN — ex.Message from the SDK/gateway doesn't include it.
                return Results.Json(new { success = false, error = ex.Message }, statusCode: 500);
            }
        });
    }

    /// <summary>GP-API returns the masked PAN (e.g. "XXXXXXXXXXXX5262"); take just the last 4 digits.</summary>
    private static string? Last4(string? masked) =>
        string.IsNullOrEmpty(masked) ? masked : masked.Length > 4 ? masked[^4..] : masked;
}
