<?php

declare(strict_types=1);

/**
 * Configuration Endpoint
 *
 * Returns environment metadata for the virtual terminal page. The access token the hosted
 * fields need is minted separately by get-access-token.php.
 *
 * PHP version 8.0 or higher
 *
 * @category  Configuration
 * @package   GlobalPayments_Sample
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

header('Content-Type: application/json');

echo json_encode([
    'success' => true,
    'data' => [
        'environment' => 'sandbox',
        'merchantName' => 'GP MOTO Demo',
    ],
]);
