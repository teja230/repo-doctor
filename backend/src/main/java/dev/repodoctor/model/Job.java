package dev.repodoctor.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private String id;

    @Column(name = "repo_url")
    private String repoUrl;

    @Column(name = "repo_name")
    private String repoName;

    @Enumerated(EnumType.STRING)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_tool")
    private BuildTool buildTool;

    @Column(name = "max_attempts")
    private int maxAttempts;

    @Column(name = "allow_network")
    private boolean allowNetwork;

    @Column(name = "workspace_path")
    private String workspacePath;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("attemptNumber ASC")
    private List<Attempt> attempts = new ArrayList<>();

    public Job() {
        this.id = UUID.randomUUID().toString();
        this.status = JobStatus.PENDING;
        this.createdAt = Instant.now();
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public BuildTool getBuildTool() {
        return buildTool;
    }

    public void setBuildTool(BuildTool buildTool) {
        this.buildTool = buildTool;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isAllowNetwork() {
        return allowNetwork;
    }

    public void setAllowNetwork(boolean allowNetwork) {
        this.allowNetwork = allowNetwork;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<Attempt> getAttempts() {
        return attempts;
    }

    public void setAttempts(List<Attempt> attempts) {
        this.attempts = attempts;
    }

    public void addAttempt(Attempt attempt) {
        attempts.add(attempt);
        attempt.setJob(this);
    }
}
