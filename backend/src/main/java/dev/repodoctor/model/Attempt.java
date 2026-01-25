package dev.repodoctor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "attempts")
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    @JsonIgnore
    private Job job;

    @Column(name = "attempt_number")
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    private AttemptStatus status;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "tests_run")
    private Integer testsRun;

    @Column(name = "tests_failed")
    private Integer testsFailed;

    @Column(name = "tests_passed")
    private Integer testsPassed;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "explanation", length = 4000)
    private String explanation;

    @Column(name = "confidence_notes", length = 2000)
    private String confidenceNotes;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    public Attempt() {
        this.status = AttemptStatus.PENDING;
        this.startedAt = Instant.now();
    }

    public Attempt(int attemptNumber) {
        this();
        this.attemptNumber = attemptNumber;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public void setStatus(AttemptStatus status) {
        this.status = status;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public Integer getTestsRun() {
        return testsRun;
    }

    public void setTestsRun(Integer testsRun) {
        this.testsRun = testsRun;
    }

    public Integer getTestsFailed() {
        return testsFailed;
    }

    public void setTestsFailed(Integer testsFailed) {
        this.testsFailed = testsFailed;
    }

    public Integer getTestsPassed() {
        return testsPassed;
    }

    public void setTestsPassed(Integer testsPassed) {
        this.testsPassed = testsPassed;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getConfidenceNotes() {
        return confidenceNotes;
    }

    public void setConfidenceNotes(String confidenceNotes) {
        this.confidenceNotes = confidenceNotes;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getJobId() {
        return job != null ? job.getId() : null;
    }
}
