package dev.repodoctor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration for CORS.
 * Allows CORS origins to be configured via environment variable.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RepoDoctorConfig config;

    public WebConfig(RepoDoctorConfig config) {
        this.config = config;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String allowedOrigins = config.getCorsAllowedOrigins();

        // Split comma-separated origins
        String[] origins = allowedOrigins.split(",");

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
