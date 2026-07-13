FROM mcr.microsoft.com/dotnet/sdk:10.0 AS builder

WORKDIR /app

# Copy project file and restore dependencies
COPY *.csproj .
RUN dotnet restore

# Copy source code and build
COPY . .
RUN dotnet publish -c Release -o out

# Runtime stage
FROM mcr.microsoft.com/dotnet/aspnet:10.0

WORKDIR /app

# Copy built application
COPY --from=builder /app/out .

# curl for the compose healthcheck (base image ships neither curl nor wget)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create non-root user
# RUN useradd --create-home --shell /bin/bash app
USER app

EXPOSE 8000

ENV ASPNETCORE_URLS=http://+:8000

CMD ["dotnet", "moto-payments.dll"]