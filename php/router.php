<?php
// Router for the PHP built-in server: serve the page, its assets and the
// endpoint scripts, and nothing else (no .env, composer files or vendor/).
// The frontend calls /config, /get-access-token and /process-payment without
// the extension, so those are mapped to their scripts first.
$path = rawurldecode(parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH) ?? '/');

$routes = [
    '/config' => 'config.php',
    '/get-access-token' => 'get-access-token.php',
    '/process-payment' => 'process-payment.php',
];
if (isset($routes[$path])) {
    require __DIR__ . '/' . $routes[$path];
    return true;
}

if ($path === '/') {
    return false;
}

$isAsset = preg_match('#^/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\.(?:html|css|js|png|jpe?g|gif|svg|ico|webp|woff2?)$#', $path);
$isEndpoint = preg_match('#^/[A-Za-z0-9_-]+\.php$#', $path) && $path !== '/router.php';
$isHidden = preg_match('#(^|/)(\.|vendor/|node_modules/)#', $path);

if (($isAsset || $isEndpoint) && !$isHidden) {
    return false;
}

http_response_code(404);
echo 'Not Found';
