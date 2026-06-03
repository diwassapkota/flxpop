# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# FlexPop engine (Spring Boot, Java 21). Multi-stage: Maven build → slim JRE.
# Build:  docker build -t flexpop-engine .
# Run:    docker run -p 8080:8080 --env-file engine.env flexpop-engine
# ---------------------------------------------------------------------------

# ----- build stage -----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Resolve dependencies first so they cache independently of source changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests need a live MySQL; they run in CI, not in the image build.
RUN mvn -B -q clean package -DskipTests
# Explode the Spring Boot layered jar so deps/loader cache separately from app code.
RUN java -Djarmode=layertools -jar target/flexpop-engine-*.jar extract --destination target/extracted

# ----- runtime stage -----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system flexpop && useradd --system --gid flexpop --home /app flexpop
# Layer order = change frequency: deps → loader → snapshots → app classes.
COPY --from=build /build/target/extracted/dependencies/ ./
COPY --from=build /build/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/target/extracted/application/ ./
USER flexpop
EXPOSE 8080
# Container-aware JVM; tune via JAVA_OPTS at deploy time.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
