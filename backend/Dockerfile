FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && mvn package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
    docker.io \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
