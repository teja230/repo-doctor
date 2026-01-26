package dev.repodoctor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for GitHub OAuth and PR creation feature.
 *
 * To enable PR creation:
 * 1. Create a GitHub OAuth App at https://github.com/settings/developers
 * 2. Set callback URL to: {your-backend-url}/api/github/callback
 * 3. Configure the client ID and secret in application.properties or env vars
 */
@Configuration
@ConfigurationProperties(prefix = "repodoctor.github")
public class GitHubConfig {

    /**
     * Enable/disable PR creation feature globally
     */
    private boolean prCreationEnabled = false;

    /**
     * GitHub OAuth App Client ID
     */
    private String clientId = "";

    /**
     * GitHub OAuth App Client Secret
     */
    private String clientSecret = "";

    /**
     * OAuth callback URL (must match the one registered in GitHub OAuth App)
     */
    private String callbackUrl = "http://localhost:8080/api/github/callback";

    /**
     * Frontend URL to redirect after OAuth completion
     */
    private String frontendUrl = "http://localhost:3000";

    /**
     * OAuth scopes required for PR creation
     */
    private String scopes = "repo";

    // Getters and setters

    public boolean isPrCreationEnabled() {
        return prCreationEnabled;
    }

    public void setPrCreationEnabled(boolean prCreationEnabled) {
        this.prCreationEnabled = prCreationEnabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getFrontendUrl() {
        return frontendUrl;
    }

    public void setFrontendUrl(String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    /**
     * Check if GitHub integration is properly configured
     */
    public boolean isConfigured() {
        return prCreationEnabled
            && clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }
}
