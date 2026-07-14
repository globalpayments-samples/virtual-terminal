<?php

declare(strict_types=1);

/**
 * Access Token Endpoint — GP-API hosted fields
 *
 * Mints a short-lived GP-API access token scoped to a single tokenisation
 * (PMT_POST_Create_Single) and hands it to the browser, so the hosted fields can turn the
 * keyed card into a single-use token without exposing the app credentials. The hash is
 * sha512(nonce + appKey), exactly as the GP-API access-token contract requires.
 *
 * PHP version 8.0 or higher
 *
 * @category  Configuration
 * @package   GlobalPayments_Sample
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

require_once 'vendor/autoload.php';

use Dotenv\Dotenv;

ini_set('display_errors', '0');

const ACCESS_TOKEN_URL = 'https://apis.sandbox.globalpay.com/ucp/accesstoken';

header('Content-Type: application/json');

// Mutable: some container env-file mechanisms don't strip inline `#comment` suffixes
// (e.g. the `#gitleaks:allow` markers), so prefer the values parsed from .env itself.
$dotenv = Dotenv::createMutable(__DIR__);
$dotenv->load();

$appId = $_ENV['GP_API_APP_ID'];
$appKey = $_ENV['GP_API_APP_KEY'];

$nonce = bin2hex(random_bytes(16));
$secret = hash('sha512', $nonce . $appKey);

$payload = json_encode([
    'app_id' => $appId,
    'nonce' => $nonce,
    'secret' => $secret,
    'grant_type' => 'client_credentials',
    'seconds_to_expire' => 600,
    'permissions' => ['PMT_POST_Create_Single'],
]);

$ch = curl_init(ACCESS_TOKEN_URL);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_HTTPHEADER => [
        'Content-Type: application/json',
        'X-GP-Version: 2021-03-22',
    ],
    CURLOPT_POSTFIELDS => $payload,
    // GP-API responds gzip-compressed; let curl advertise and decode the encoding so the
    // body is plain JSON (an empty string enables every encoding curl was built with).
    CURLOPT_ENCODING => '',
]);

$responseBody = curl_exec($ch);
$httpStatus = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

$data = $responseBody ? json_decode($responseBody, true) : null;

if ($httpStatus < 200 || $httpStatus >= 300 || empty($data['token'])) {
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'error' => $data['error_description'] ?? 'Failed to generate access token',
    ]);
    exit;
}

echo json_encode([
    'success' => true,
    'token' => $data['token'],
    'expiresIn' => $data['seconds_to_expire'] ?? 600,
]);
