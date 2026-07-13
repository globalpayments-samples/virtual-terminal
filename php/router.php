<?php

/**
 * Router for PHP's built-in server (php -S host:port router.php).
 *
 * Maps the clean endpoint paths used by the frontend (/config, /process-payment) to
 * their PHP scripts, and otherwise lets the built-in server serve static files
 * (index.html, gp-checkout.css) directly.
 */

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);

if ($path === '/config') {
    require __DIR__ . '/config.php';
    return true;
}

if ($path === '/process-payment') {
    require __DIR__ . '/process-payment.php';
    return true;
}

// Let the built-in server handle static files (index.html, gp-checkout.css, etc.)
return false;
