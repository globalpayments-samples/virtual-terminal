FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install Maven (needed for Cargo plugin)
RUN apk add --no-cache maven

# Copy built application and pom.xml
COPY --from=builder /app/target ./target
COPY --from=builder /app/pom.xml .
COPY --from=builder /root/.m2 /root/.m2

# Create non-root user and hand it ownership of the app dir (Cargo needs to
# create working folders under target/ at runtime)
RUN addgroup -g 1001 -S javauser && \
    adduser -S javauser -u 1001 && \
    chown -R javauser:javauser /app
USER javauser

EXPOSE 8000

CMD ["mvn", "cargo:run", "-Dquiet"]