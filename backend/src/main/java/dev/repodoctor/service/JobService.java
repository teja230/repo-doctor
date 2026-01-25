package dev.repodoctor.service;

import dev.repodoctor.config.RepoDoctorConfig;
import dev.repodoctor.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service for creating and managing jobs.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final RepoDoctorConfig config;
    private final Orchestrator orchestrator;

    public JobService(JobRepository jobRepository, RepoDoctorConfig config, Orchestrator orchestrator) {
        this.jobRepository = jobRepository;
        this.config = config;
        this.orchestrator = orchestrator;
    }

    /**
     * Create a new job from a ZIP file upload.
     */
    public Job createJobFromZip(MultipartFile zipFile, int maxAttempts, boolean allowNetwork)
            throws IOException {

        // Validate file size
        if (zipFile.getSize() > config.getMaxZipSizeMb() * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "ZIP file too large. Maximum size is " + config.getMaxZipSizeMb() + "MB");
        }

        // Create job
        Job job = new Job();
        job.setMaxAttempts(Math.min(maxAttempts, config.getMaxAttemptsLimit()));
        job.setAllowNetwork(allowNetwork);
        job.setRepoName(getRepoNameFromFilename(zipFile.getOriginalFilename()));

        // Create workspace directory
        Path workspacesRoot = Path.of(config.getWorkspacesPath());
        Files.createDirectories(workspacesRoot);
        Path workspacePath = workspacesRoot.resolve(job.getId());
        Files.createDirectories(workspacePath);
        job.setWorkspacePath(workspacePath.toString());

        // Extract ZIP
        int fileCount = extractZip(zipFile.getInputStream(), workspacePath);

        if (fileCount > config.getMaxFileCount()) {
            // Cleanup and reject
            deleteDirectory(workspacePath);
            throw new IllegalArgumentException(
                    "Too many files in archive. Maximum is " + config.getMaxFileCount() +
                            " files, but found " + fileCount);
        }

        job.setStatus(JobStatus.PENDING);
        jobRepository.save(job);

        log.info("Created job {} from ZIP with {} files", job.getId(), fileCount);

        // Start async processing
        orchestrator.runJob(job.getId());

        return job;
    }

    /**
     * Create a new job from a GitHub URL.
     */
    public Job createJobFromUrl(String repoUrl, int maxAttempts, boolean allowNetwork)
            throws IOException {

        // Create job
        Job job = new Job();
        job.setRepoUrl(repoUrl);
        job.setMaxAttempts(Math.min(maxAttempts, config.getMaxAttemptsLimit()));
        job.setAllowNetwork(allowNetwork);
        job.setRepoName(getRepoNameFromUrl(repoUrl));

        // Create workspace directory
        Path workspacesRoot = Path.of(config.getWorkspacesPath());
        Files.createDirectories(workspacesRoot);
        Path workspacePath = workspacesRoot.resolve(job.getId());
        Files.createDirectories(workspacePath);
        job.setWorkspacePath(workspacePath.toString());

        job.setStatus(JobStatus.CLONING);
        jobRepository.save(job);

        // Clone the repository
        cloneRepository(repoUrl, workspacePath);

        // Count files
        long fileCount;
        try (var walk = Files.walk(workspacePath)) {
            fileCount = walk.filter(Files::isRegularFile).count();
        }

        if (fileCount > config.getMaxFileCount()) {
            deleteDirectory(workspacePath);
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Repository has too many files: " + fileCount);
            jobRepository.save(job);
            throw new IllegalArgumentException(
                    "Too many files in repository. Maximum is " + config.getMaxFileCount());
        }

        log.info("Created job {} from URL {} with {} files", job.getId(), repoUrl, fileCount);

        // Start async processing
        orchestrator.runJob(job.getId());

        return job;
    }

    public Job getJob(String jobId) {
        return jobRepository.findById(jobId).orElse(null);
    }

    public List<Job> getRecentJobs() {
        return jobRepository.findTop10ByOrderByCreatedAtDesc();
    }

    public boolean deleteJob(String jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return false;
        }

        // Delete workspace directory
        if (job.getWorkspacePath() != null) {
            deleteDirectory(Path.of(job.getWorkspacePath()));
        }

        // Delete job (cascades to attempts)
        jobRepository.delete(job);
        log.info("Deleted job {}", jobId);
        return true;
    }

    private int extractZip(InputStream inputStream, Path destination) throws IOException {
        int fileCount = 0;
        long totalSize = 0;
        long maxUnzippedSize = 200L * 1024 * 1024; // 200MB limit

        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // Security: prevent zip slip - validate resolved path is within destination
                Path entryPath = destination.resolve(entryName).normalize();

                // Verify the normalized path is still within the destination directory
                if (!entryPath.startsWith(destination.normalize())) {
                    throw new IOException("Invalid ZIP entry path (attempts to escape workspace): " + entryName);
                }

                // Also reject absolute paths
                if (entryName.startsWith("/") || entryName.contains("..")) {
                    throw new IOException("Invalid ZIP entry path (contains absolute path or ..): " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // Create parent directories
                    if (entryPath.getParent() != null) {
                        Files.createDirectories(entryPath.getParent());
                    }

                    // Copy file with size check
                    try (OutputStream os = Files.newOutputStream(entryPath)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            totalSize += bytesRead;
                            if (totalSize > maxUnzippedSize) {
                                throw new IOException("Unzipped size exceeds 200MB limit");
                            }
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                    fileCount++;
                }

                zis.closeEntry();
            }
        }

        return fileCount;
    }

    private void cloneRepository(String url, Path destination) throws IOException {
        // Validate URL to prevent SSRF attacks
        validateRepositoryUrl(url);

        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "--depth", "1", url, destination.toString());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IOException("Git clone failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git clone interrupted", e);
        }
    }

    /**
     * Validate repository URL to prevent SSRF and other attacks.
     * Only allows https URLs from GitHub, GitLab, and Bitbucket.
     */
    private void validateRepositoryUrl(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("Repository URL is required");
        }

        // Allowed hosts for git repositories
        Set<String> allowedHosts = Set.of(
                "github.com", "www.github.com",
                "gitlab.com", "www.gitlab.com",
                "bitbucket.org", "www.bitbucket.org"
        );

        try {
            URI uri = new URI(url);

            // Only allow https protocol (or git for GitHub SSH style)
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("https") && !scheme.equals("git"))) {
                throw new IOException("Only HTTPS URLs are allowed for repository cloning. Got: " + scheme);
            }

            // Check if host is in allowed list
            String host = uri.getHost();
            if (host == null || !allowedHosts.contains(host.toLowerCase())) {
                throw new IOException("Only GitHub, GitLab, and Bitbucket repositories are allowed. Got: " + host);
            }

            // Reject localhost and internal IPs
            if (host.toLowerCase().contains("localhost") ||
                    host.startsWith("127.") ||
                    host.startsWith("10.") ||
                    host.startsWith("192.168.") ||
                    host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")) {
                throw new IOException("Local and internal network addresses are not allowed");
            }

        } catch (URISyntaxException e) {
            throw new IOException("Invalid repository URL format: " + e.getMessage(), e);
        }
    }

    private String getRepoNameFromFilename(String filename) {
        if (filename == null)
            return "unknown";
        return filename.replaceAll("\\.(zip|tar\\.gz|tgz)$", "");
    }

    private String getRepoNameFromUrl(String url) {
        // Extract repo name from GitHub URL
        String cleaned = url.replaceAll("\\.git$", "");
        int lastSlash = cleaned.lastIndexOf('/');
        if (lastSlash >= 0) {
            return cleaned.substring(lastSlash + 1);
        }
        return "unknown";
    }

    private void deleteDirectory(Path path) {
        try {
            Files.walk(path)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", p);
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to delete directory: {}", path, e);
        }
    }
}
