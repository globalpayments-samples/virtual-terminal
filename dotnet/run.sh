#!/bin/bash

# Exit on error
set -e

# Install requirements
dotnet restore

# Start the server
dotnet run
