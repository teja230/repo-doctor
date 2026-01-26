package dev.repodoctor.config;

import dev.repodoctor.service.ApplicationRunner;
import dev.repodoctor.service.DirectRunner;
import dev.repodoctor.service.DockerRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for selecting the ApplicationRunner implementation.
 * 
 * Modes:
 * - "docker": Use DockerRunner (sandboxed, requires Docker socket)
 * - "direct": Use DirectRunner (fast, for managed platforms like Render)
 */
@Configuration
public class RunnerConfig {

    private static final Logger log = LoggerFactory.getLogger(RunnerConfig.class);

    @Bean
    @Primary
    public ApplicationRunner applicationRunner(RepoDoctorConfig config,
            DockerRunner dockerRunner,
            DirectRunner directRunner) {
        String mode = config.getRunnerMode();

        if ("direct".equalsIgnoreCase(mode)) {
            log.info("Using DirectRunner (direct process execution)");
            log.warn("Security: Running tests without Docker isolation");
            return directRunner;
        }

        log.info("Using DockerRunner (sandboxed execution)");
        return dockerRunner;
    }
}
