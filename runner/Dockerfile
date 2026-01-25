# Build tools runner image for RepoDoctor
FROM eclipse-temurin:21-jdk-jammy

# Install build tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install Maven
ARG MAVEN_VERSION=3.9.6
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xzC /opt \
    && ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven
ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# Install Gradle
ARG GRADLE_VERSION=8.5
RUN curl -fsSL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip \
    && ln -s /opt/gradle-${GRADLE_VERSION} /opt/gradle
ENV GRADLE_HOME=/opt/gradle
ENV PATH="${GRADLE_HOME}/bin:${PATH}"

# Install Node.js 20
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r runner && useradd -r -g runner -d /workspace -s /sbin/nologin runner

# Setup workspace
WORKDIR /workspace
RUN chown -R runner:runner /workspace

# Switch to non-root user
USER runner

# Default command
ENTRYPOINT ["/bin/bash", "-c"]
CMD ["echo 'RepoDoctor Runner ready'"]
