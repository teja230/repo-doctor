package dev.repodoctor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "repodoctor")
public class RepoDoctorConfig {

    private String artifactsPath = "./artifacts";
    private String workspacesPath = "./workspaces";
    private String runnerImage = "repodoctor-runner:latest";
    private int maxAttemptsLimit = 10;
    private int maxZipSizeMb = 25;
    private int maxFileCount = 250;
    private int containerTimeoutSeconds = 300;
    private int containerMemoryMb = 2048;
    private double containerCpus = 1.0;
    private GeminiConfig gemini = new GeminiConfig();
    private String corsAllowedOrigins = "http://localhost:3000,http://localhost:8080";

    public static class GeminiConfig {
        private String apiKey;
        private String proModel;
        private String flashModel;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getProModel() {
            return proModel;
        }

        public void setProModel(String proModel) {
            this.proModel = proModel;
        }

        public String getFlashModel() {
            return flashModel;
        }

        public void setFlashModel(String flashModel) {
            this.flashModel = flashModel;
        }

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    // Getters and setters
    public String getArtifactsPath() {
        return artifactsPath;
    }

    public void setArtifactsPath(String artifactsPath) {
        this.artifactsPath = artifactsPath;
    }

    public String getWorkspacesPath() {
        return workspacesPath;
    }

    public void setWorkspacesPath(String workspacesPath) {
        this.workspacesPath = workspacesPath;
    }

    public String getRunnerImage() {
        return runnerImage;
    }

    public void setRunnerImage(String runnerImage) {
        this.runnerImage = runnerImage;
    }

    public int getMaxAttemptsLimit() {
        return maxAttemptsLimit;
    }

    public void setMaxAttemptsLimit(int maxAttemptsLimit) {
        this.maxAttemptsLimit = maxAttemptsLimit;
    }

    public int getMaxZipSizeMb() {
        return maxZipSizeMb;
    }

    public void setMaxZipSizeMb(int maxZipSizeMb) {
        this.maxZipSizeMb = maxZipSizeMb;
    }

    public int getMaxFileCount() {
        return maxFileCount;
    }

    public void setMaxFileCount(int maxFileCount) {
        this.maxFileCount = maxFileCount;
    }

    public int getContainerTimeoutSeconds() {
        return containerTimeoutSeconds;
    }

    public void setContainerTimeoutSeconds(int containerTimeoutSeconds) {
        this.containerTimeoutSeconds = containerTimeoutSeconds;
    }

    public int getContainerMemoryMb() {
        return containerMemoryMb;
    }

    public void setContainerMemoryMb(int containerMemoryMb) {
        this.containerMemoryMb = containerMemoryMb;
    }

    public double getContainerCpus() {
        return containerCpus;
    }

    public void setContainerCpus(double containerCpus) {
        this.containerCpus = containerCpus;
    }

    public GeminiConfig getGemini() {
        return gemini;
    }

    public void setGemini(GeminiConfig gemini) {
        this.gemini = gemini;
    }

    public String getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(String corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
