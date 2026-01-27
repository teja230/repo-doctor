FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY backend/pom.xml .
COPY backend/src ./src
RUN apt-get update && apt-get install -y maven && mvn package -DskipTests

FROM eclipse-temurin:21-jdk-jammy
# Install tools needed for DirectRunner mode:
# - maven: for Maven-based Java projects (requires JDK)
# - gradle: for Gradle-based Java projects
# - nodejs & npm: for Node.js/JavaScript projects
# - git: for repository operations
# Note: Using JDK instead of JRE because DirectRunner compiles user projects
RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    gradle \
    nodejs \
    npm \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
