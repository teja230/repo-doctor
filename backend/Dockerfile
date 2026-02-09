FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY backend/pom.xml .
COPY backend/src ./src
RUN apt-get update && apt-get install -y maven && mvn package -DskipTests

FROM eclipse-temurin:21-jdk-jammy
# Install tools needed for both DirectRunner and DockerRunner modes:
# - maven: for Maven-based Java projects (requires JDK)
# - gradle: for Gradle-based Java projects
# - nodejs & npm: for Node.js/JavaScript projects
# - python3 & pip: for Python projects with pytest
# - git: for repository operations and patch management
# - docker.io: Docker CLI for DockerRunner mode (uses mounted socket)
# Note: Using JDK instead of JRE because DirectRunner compiles user projects
# Note: docker.io provides only the CLI client, not the daemon - safe with socket mounting
RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    gradle \
    nodejs \
    npm \
    python3 \
    python3-pip \
    git \
    docker.io \
    && pip3 install --no-cache-dir pytest \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# JVM startup optimization flags for faster cloud deployment:
# -XX:TieredStopAtLevel=1: Use C1 compiler only (faster startup, slightly slower runtime)
# -XX:+UseStringDeduplication: Reduce memory footprint
# -XX:InitialRAMPercentage/-XX:MaxRAMPercentage: container-aware heap sizing
ENTRYPOINT ["java", \
    "-XX:TieredStopAtLevel=1", \
    "-XX:+UseStringDeduplication", \
    "-XX:InitialRAMPercentage=20.0", \
    "-XX:MaxRAMPercentage=70.0", \
    "-jar", "app.jar"]
