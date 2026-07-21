# Cyrene Agent - Multi-stage Docker Build
# Stage 1: Maven build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
# Download dependencies first (layer cache)
RUN mvn dependency:go-offline -B
COPY harness-env/pom.xml harness-env/
COPY harness-core/pom.xml harness-core/
COPY harness-input/pom.xml harness-input/
COPY harness-preprocess/pom.xml harness-preprocess/
COPY harness-ai/pom.xml harness-ai/
COPY harness-tool/pom.xml harness-tool/
COPY harness-audit/pom.xml harness-audit/
COPY harness-agent/pom.xml harness-agent/
COPY harness-server/pom.xml harness-server/
RUN mvn dependency:go-offline -B || true
COPY . .
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/harness-server/target/harness-server-*.jar app.jar
# Expose HTTP port
EXPOSE 8080
# .env file is mounted at runtime
ENTRYPOINT ["java", "-jar", "app.jar"]
