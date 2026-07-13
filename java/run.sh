#!/bin/bash

# Exit on error
set -e

# Install requirements, and Start the server
mvn clean package cargo:run
