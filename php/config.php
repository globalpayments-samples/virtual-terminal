<?php

declare(strict_types=1);

/**
 * Configuration Endpoint
 *
 * MOTO is server-side keyed entry (the agent types the card into a plain form), so
 * there is no public key or hosted fields to expose to the browser.
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
