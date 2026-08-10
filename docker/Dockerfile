# Cyrene Agent - Multi-stage Docker Build
# Stage 1: Maven build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY harness-core/pom.xml harness-core/
COPY harness-input/pom.xml harness-input/
COPY harness-provider/pom.xml harness-provider/
COPY harness-tool/pom.xml harness-tool/
COPY harness-react/pom.xml harness-react/
COPY harness-trace/pom.xml harness-trace/
COPY harness-agent/pom.xml harness-agent/
COPY harness-server/pom.xml harness-server/
# Download dependencies first so source-only changes reuse this layer.
RUN mvn dependency:go-offline -B
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
