<?php

declare(strict_types=1);

/**
 * MOTO Payment Processing — GP-API (Unified Payments)
 *
 * Agent-facing "virtual terminal" endpoint. The card is keyed into hosted fields in the
 * browser and tokenised there, so this endpoint receives a single-use token
 * (payment_reference), never the PAN. It charges that token with entryMethod = MOTO.
 * Flagging a transaction as MOTO exempts it from SCA/3DS2 under PSD2. Optionally stores the
 * credential (COF) so the returned brand reference can be persisted for future
 * merchant-initiated transactions (MITs).
 *
 * PHP version 8.0 or higher
 *
 * @category  Payment_Processing
 * @package   GlobalPayments_Sample
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

require_once 'vendor/autoload.php';

use Dotenv\Dotenv;
use GlobalPayments\Api\Entities\Address;
use GlobalPayments\Api\Entities\Enums\AddressType;
use GlobalPayments\Api\Entities\Enums\Channel;
use GlobalPayments\Api\Entities\Enums\ManualEntryMethod;
use GlobalPayments\Api\Entities\Enums\StoredCredentialInitiator;
use GlobalPayments\Api\Entities\Enums\StoredCredentialSequence;
use GlobalPayments\Api\Entities\Enums\StoredCredentialType;
use GlobalPayments\Api\Entities\Exceptions\ApiException;
use GlobalPayments\Api\Entities\StoredCredential;
use GlobalPayments\Api\PaymentMethods\CreditCardData;
use GlobalPayments\Api\ServiceConfigs\Gateways\GpApiConfig;
use GlobalPayments\Api\ServicesContainer;
use GlobalPayments\Api\Utils\GenerationUtils;

ini_set('display_errors', '0');

/**
 * Configure the GP-API (Unified Payments) SDK for sandbox, card-not-present.
 *
 * @return void
 */
function configureSdk(): void
{
    // Mutable: some container env-file mechanisms don't strip inline `#comment`
    // suffixes (e.g. the `#gitleaks:allow` markers below), so prefer the values
    // parsed from .env itself, which does strip them correctly.
    $dotenv = Dotenv::createMutable(__DIR__);
    $dotenv->load();

    $config = new GpApiConfig();
    $config->appId = $_ENV['GP_API_APP_ID'];
    $config->appKey = $_ENV['GP_API_APP_KEY'];
    $config->country = $_ENV['GP_API_COUNTRY'] ?? 'IE';
    $config->channel = Channel::CardNotPresent;
    // No requestLogger is configured: the default SDK logging would otherwise dump the PAN.

    ServicesContainer::configureService($config);
}

header('Content-Type: application/json');

// Initialize SDK configuration
configureSdk();

try {
    $body = json_decode((string) file_get_contents('php://input'), true) ?? [];

    // Validate required fields.
    $required = ['payment_reference', 'amount'];
    foreach ($required as $field) {
        if (empty($body[$field])) {
            http_response_code(400);
            echo json_encode([
                'success' => false,
                'error' => 'Missing required field(s): payment_reference, amount',
            ]);
            exit;
        }
    }

    // The hosted fields already captured and tokenised the card; charge the token with
    // entryMethod = MOTO — the whole point of this sample (flags it as MOTO / SCA-exempt).
    $card = new CreditCardData();
    $card->token = $body['payment_reference'];
    $card->entryMethod = ManualEntryMethod::MOTO;

    $currency = $body['currency'] ?? 'EUR';

    // Build the charge; MOTO is CNP so a client transaction id ties it back to our order.
    $orderId = GenerationUtils::generateOrderId();
    $builder = $card->charge((float) $body['amount'])
        ->withCurrency($currency)
        ->withClientTransactionId($orderId);

    // Billing address (AVS) when provided.
    if (!empty($body['billing_address'])) {
        $billingAddress = $body['billing_address'];
        $address = new Address();
        $address->streetAddress1 = $billingAddress['line1'] ?? null;
        $address->city = $billingAddress['city'] ?? null;
        $address->state = $billingAddress['state'] ?? null;
        $address->postalCode = $billingAddress['postal_code'] ?? null;
        $address->countryCode = $billingAddress['country'] ?? null;
        $builder = $builder->withAddress($address, AddressType::BILLING);
    }

    // Credential on File: mark this as the first storage of the card so the returned
    // brand reference can be persisted for later merchant-initiated MOTO charges.
    if (!empty($body['store_card'])) {
        $storedCredential = new StoredCredential();
        $storedCredential->initiator = StoredCredentialInitiator::PAYER;
        $storedCredential->type = StoredCredentialType::UNSCHEDULED;
        $storedCredential->sequence = StoredCredentialSequence::FIRST;
        $builder = $builder->withStoredCredential($storedCredential);
    }

    $response = $builder->execute();

    echo json_encode([
        'success' => true,
        'data' => [
            'transactionId' => $response->transactionId,
            'status' => $response->responseMessage, // e.g. CAPTURED
            'cardLast4' => $response->cardLast4 ? substr($response->cardLast4, -4) : null,
            'brand' => $response->cardType, // e.g. VISA
            'authCode' => $response->authorizationCode,
            'brandReference' => $response->cardBrandTransactionId, // persist for future MITs
            'amount' => $body['amount'],
            'currency' => $currency,
            'reference' => $orderId,
        ],
    ]);
} catch (ApiException $e) {
    // Both SDK and validation errors surface only a message — never the PAN.
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'error' => $e->getMessage(),
    ]);
}
