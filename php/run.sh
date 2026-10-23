#!/bin/bash

# Exit on error
set -e

if ! command -v php >/dev/null 2>&1 || ! php -r '$branch = PHP_MAJOR_VERSION . "." . PHP_MINOR_VERSION; $minimums = ["8.2" => "8.2.35", "8.3" => "8.3.36", "8.4" => "8.4.27", "8.5" => "8.5.12"]; exit(isset($minimums[$branch]) && version_compare(PHP_VERSION, $minimums[$branch], ">=") ? 0 : 1);'; then
    printf 'A supported patched PHP release is required (8.2.35+, 8.3.36+, 8.4.27+, or 8.5.12+). Upgrade PHP and try again.\n' >&2
    exit 1
fi

# Install requirements
composer install

# Start the server
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8000}"
exec php -S "$HOST:$PORT" router.php
