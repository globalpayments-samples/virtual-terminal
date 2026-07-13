using System.Text.Json;
using GlobalPayments.Api;
using GlobalPayments.Api.Entities;
using GlobalPayments.Api.PaymentMethods;
using dotenv.net;

namespace MotoPaymentSample;

/// <summary>
/// MOTO (Mail Order / Telephone Order) Payment Sample
///
/// Demonstrates a virtual-terminal style flow: an agent keys a card in directly
/// (no hosted fields/tokenization) and the server charges it via GP-API with
/// entry_mode = MOTO, which exempts the transaction from SCA/3DS2 under PSD2.
/// Optionally stores the card as a Credential on File (COF) for later
/// merchant-initiated transactions.
/// </summary>
public class Program
{
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
    /// Configures the GP-API SDK for MOTO (card-not-present, manually keyed) transactions.
    /// No RequestLogger is set — MOTO handles raw PANs and the SDK's request logger would
    /// otherwise dump them.
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

        ConfigurePaymentEndpoint(app);
    }

    /// <summary>
    /// Charges a manually-keyed card via GP-API MOTO. Body is snake_case JSON per the sample
    /// contract; the response is camelCase (ASP.NET Core minimal APIs default to the Web JSON
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

            var cardNumber = Str("card_number");
            var expMonth = Str("exp_month");
            var expYear = Str("exp_year");
            var cvv = Str("cvv");
            var amountStr = Str("amount");
            var cardholderName = Str("cardholder_name");
            var currency = Str("currency") ?? "EUR";

            if (string.IsNullOrEmpty(cardNumber) || string.IsNullOrEmpty(expMonth) ||
                string.IsNullOrEmpty(expYear) || string.IsNullOrEmpty(cvv) ||
                string.IsNullOrEmpty(amountStr) ||
                !decimal.TryParse(amountStr, out var amount) || amount <= 0)
            {
                return Results.Json(new { success = false, error = "Missing or invalid required fields" }, statusCode: 400);
            }

            // entryMethod = MOTO is the whole point of this sample: it flags the transaction
            // as agent-keyed so GP-API exempts it from SCA/3DS2.
            var card = new CreditCardData
            {
                Number = cardNumber,
                ExpMonth = int.Parse(expMonth),
                ExpYear = int.Parse(expYear),
                Cvn = cvv,
                CardHolderName = cardholderName,
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
