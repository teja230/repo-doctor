FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY backend/pom.xml .
COPY backend/src ./src
RUN apt-get update && apt-get install -y maven && mvn package -DskipTests

FROM eclipse-temurin:21-jre-jammy
# Install tools needed for DirectRunner mode:
# - maven: for running Maven-based projects
# - git: for repository operations
# Note: docker.io only needed for DockerRunner mode (not used on Render)
RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
