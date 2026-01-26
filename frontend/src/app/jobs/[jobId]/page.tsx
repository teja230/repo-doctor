"use client";

import { useEffect, useState, useCallback, use } from "react";
import Link from "next/link";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface JobEvent {
    type: string;
    timestamp: string;
    data: Record<string, unknown>;
}

interface Attempt {
    attemptNumber: number;
    status: string;
    exitCode: number | null;
    testsRun: number | null;
    testsFailed: number | null;
    testsPassed: number | null;
    startedAt: string;
    completedAt: string | null;
    explanation: string | null;
    confidenceNotes: string | null;
    riskLevel: string | null;
    errorMessage: string | null;
}

interface Job {
    id: string;
    repoName: string;
    repoUrl: string | null;
    status: string;
    buildTool: string | null;
    maxAttempts: number;
    allowNetwork: boolean;
    createdAt: string;
    completedAt: string | null;
    errorMessage: string | null;
    attemptCount: number;
    llmConfigured: boolean;
}

// Helper to get exit code explanation
const getExitCodeExplanation = (exitCode: number | null): { title: string; description: string; isDockerError: boolean } => {
    switch (exitCode) {
        case 125:
            return {
                title: "Docker Configuration Issue",
                description: "The workspace directory is not shared with Docker. You need to configure Docker Desktop to allow file sharing.",
                isDockerError: true
            };
        case 126:
            return {
                title: "Command Not Executable",
                description: "The build command could not be executed. Check if the build tool is properly installed.",
                isDockerError: false
            };
        case 127:
            return {
                title: "Command Not Found",
                description: "The build command was not found in the container. The project may require a different build tool.",
                isDockerError: false
            };
        case 137:
            return {
                title: "Out of Memory",
                description: "The container was killed due to memory limits. Try increasing Docker memory allocation.",
                isDockerError: true
            };
        case 143:
            return {
                title: "Container Terminated",
                description: "The container was terminated by a signal. This may indicate a timeout.",
                isDockerError: true
            };
        default:
            return {
                title: "Build Failed",
                description: exitCode ? `Build process exited with code ${exitCode}` : "Build process failed",
                isDockerError: false
            };
    }
};

// Helper to format duration
const formatDuration = (start: string, end: string | null): string => {
    if (!end) return "Running...";
    const startDate = new Date(start);
    const endDate = new Date(end);
    const diffMs = endDate.getTime() - startDate.getTime();
    const diffSec = Math.floor(diffMs / 1000);
    if (diffSec < 60) return `${diffSec}s`;
    const mins = Math.floor(diffSec / 60);
    const secs = diffSec % 60;
    return `${mins}m ${secs}s`;
};

// Helper to get risk level color
const getRiskLevelColor = (level: string | null): string => {
    switch (level?.toUpperCase()) {
        case "LOW": return "text-green-400 bg-green-500/20";
        case "MEDIUM": return "text-yellow-400 bg-yellow-500/20";
        case "HIGH": return "text-red-400 bg-red-500/20";
        default: return "text-gray-400 bg-gray-500/20";
    }
};

export default function JobPage({ params }: { params: Promise<{ jobId: string }> }) {
    const { jobId } = use(params);
    const [job, setJob] = useState<Job | null>(null);
    const [attempts, setAttempts] = useState<Attempt[]>([]);
    const [events, setEvents] = useState<JobEvent[]>([]);
    const [selectedAttempt, setSelectedAttempt] = useState<number | null>(null);
    const [viewMode, setViewMode] = useState<"logs" | "diff" | "ai">("logs");
    const [content, setContent] = useState<string>("");
    const [loading, setLoading] = useState(true);
    const [connected, setConnected] = useState(false);
    const [expandedExplanation, setExpandedExplanation] = useState<number | null>(null);
    const [copySuccess, setCopySuccess] = useState(false);
    const [copyDiffSuccess, setCopyDiffSuccess] = useState(false);

    // GitHub PR creation state
    const [githubEnabled, setGithubEnabled] = useState(false);
    const [creatingPR, setCreatingPR] = useState(false);
    const [prError, setPrError] = useState<string | null>(null);

    // Get currently selected attempt object
    const currentAttempt = attempts.find(a => a.attemptNumber === selectedAttempt);

    // Fetch job data
    const fetchJob = useCallback(async () => {
        try {
            console.log(`[fetchJob] Fetching job: ${API_URL}/api/jobs/${jobId}`);
            const response = await fetch(`${API_URL}/api/jobs/${jobId}`);
            console.log(`[fetchJob] Response status: ${response.status}`);
            if (response.ok) {
                const data = await response.json();
                console.log(`[fetchJob] Job data:`, data);
                setJob(data);
            } else {
                console.error(`[fetchJob] Failed with status ${response.status}: ${response.statusText}`);
            }
        } catch (error) {
            console.error("[fetchJob] Failed to fetch job:", error);
        }
    }, [jobId]);

    // Fetch attempts
    const fetchAttempts = useCallback(async () => {
        try {
            console.log(`[fetchAttempts] Fetching attempts: ${API_URL}/api/jobs/${jobId}/attempts`);
            const response = await fetch(`${API_URL}/api/jobs/${jobId}/attempts`);
            console.log(`[fetchAttempts] Response status: ${response.status}`);
            if (response.ok) {
                const data = await response.json();
                console.log(`[fetchAttempts] Attempts data:`, data);
                setAttempts(data);
                if (data.length > 0 && selectedAttempt === null) {
                    setSelectedAttempt(data[data.length - 1].attemptNumber);
                }
            } else {
                console.error(`[fetchAttempts] Failed with status ${response.status}: ${response.statusText}`);
            }
        } catch (error) {
            console.error("[fetchAttempts] Failed to fetch attempts:", error);
        }
    }, [jobId, selectedAttempt]);

    // Fetch content (logs or diff)
    const fetchContent = useCallback(async (attemptNum: number, mode: "logs" | "diff") => {
        setContent(""); // Clear previous content immediately
        try {
            const response = await fetch(`${API_URL}/api/jobs/${jobId}/attempts/${attemptNum}/${mode}`);
            if (response.ok) {
                const text = await response.text();
                setContent(text || `No ${mode} available for this attempt.`);
            } else {
                setContent(`Failed to load ${mode}. HTTP ${response.status}: ${response.statusText}`);
            }
        } catch (error) {
            console.error(`Failed to fetch ${mode}:`, error);
            setContent(`Error loading ${mode}: ${error instanceof Error ? error.message : 'Network error'}`);
        }
    }, [jobId]);

    // Check if GitHub PR creation is enabled
    const checkGitHubStatus = useCallback(async () => {
        try {
            const response = await fetch(`${API_URL}/api/github/status`);
            if (response.ok) {
                const data = await response.json();
                setGithubEnabled(data.enabled);
            }
        } catch (error) {
            console.error("Failed to check GitHub status:", error);
        }
    }, []);

    // Initial load
    useEffect(() => {
        fetchJob();
        fetchAttempts();
        checkGitHubStatus();
        setLoading(false);
    }, [fetchJob, fetchAttempts, checkGitHubStatus]);

    // SSE connection
    useEffect(() => {
        console.log(`[SSE] Connecting to: ${API_URL}/api/jobs/${jobId}/events`);
        const eventSource = new EventSource(`${API_URL}/api/jobs/${jobId}/events`);

        eventSource.onopen = () => {
            console.log("[SSE] Connection opened");
            setConnected(true);
        };

        eventSource.onmessage = (event) => {
            console.log("[SSE] Message received:", event.data);
            try {
                const data = JSON.parse(event.data);
                console.log("[SSE] Parsed event:", data);
                setEvents((prev) => [...prev.slice(-50), data]);

                // Refresh data on any significant event
                if (["attempt_started", "attempt_completed", "job_completed", "patch_applied", "patch_proposed", "run_completed"].includes(data.type)) {
                    console.log(`[SSE] Triggering refresh for event type: ${data.type}`);
                    fetchJob();
                    fetchAttempts();
                }
            } catch (e) {
                console.error("[SSE] Failed to parse SSE event:", e);
            }
        };

        eventSource.onerror = (error) => {
            console.error("[SSE] Connection error:", error);
            setConnected(false);
        };

        return () => {
            console.log("[SSE] Closing connection");
            eventSource.close();
        };
    }, [jobId, fetchJob, fetchAttempts]);

    // Fetch content when selection changes
    useEffect(() => {
        if (selectedAttempt !== null && viewMode !== "ai") {
            fetchContent(selectedAttempt, viewMode);
        }
    }, [selectedAttempt, viewMode, fetchContent]);

    // Keyboard shortcuts
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            // Don't trigger if user is typing in an input
            if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

            switch (e.key.toLowerCase()) {
                case "l":
                    setViewMode("logs");
                    break;
                case "d":
                    if (selectedAttempt !== 0) setViewMode("diff");
                    break;
                case "a":
                    setViewMode("ai");
                    break;
                case "arrowup":
                    e.preventDefault();
                    if (selectedAttempt !== null && selectedAttempt > 0) {
                        setSelectedAttempt(selectedAttempt - 1);
                    }
                    break;
                case "arrowdown":
                    e.preventDefault();
                    if (selectedAttempt !== null && selectedAttempt < attempts.length - 1) {
                        setSelectedAttempt(selectedAttempt + 1);
                    }
                    break;
            }
        };

        window.addEventListener("keydown", handleKeyDown);
        return () => window.removeEventListener("keydown", handleKeyDown);
    }, [selectedAttempt, attempts.length]);

    const getStatusClass = (status: string) => {
        const statusLower = status?.toLowerCase() || "";
        if (statusLower.includes("success") || statusLower.includes("completed")) return "status-success";
        if (statusLower.includes("fail") || statusLower.includes("error") || statusLower.includes("invalid")) return "status-failed";
        if (statusLower.includes("pause") || statusLower.includes("wait") || statusLower.includes("rate_limit")) return "status-warning";
        if (statusLower.includes("running") || statusLower.includes("pending") || statusLower.includes("analyzing") || statusLower.includes("patching")) return "status-running";
        return "status-pending";
    };

    const getTimelineClass = (attempt: Attempt) => {
        const status = attempt.status;
        if (status === "SUCCESS") return "success";
        if (status?.includes("FAIL") || status?.includes("ERROR") || status?.includes("INVALID")) return "failed";
        if (status === "RUNNING" || status === "PENDING" || status === "ANALYZING" || status === "PATCHING" || status === "RATE_LIMIT_PAUSE") return "running";
        return "";
    };

    const formatDiff = (diff: string) => {
        return diff.split("\n").map((line, i) => {
            let className = "";
            if (line.startsWith("+") && !line.startsWith("+++")) className = "diff-add";
            else if (line.startsWith("-") && !line.startsWith("---")) className = "diff-remove";
            else if (line.startsWith("@@") || line.startsWith("diff") || line.startsWith("index")) className = "diff-meta";
            return (
                <div key={i} className={className}>
                    {line || " "}
                </div>
            );
        });
    };

    const copyJobLink = async () => {
        try {
            await navigator.clipboard.writeText(window.location.href);
            setCopySuccess(true);
            setTimeout(() => setCopySuccess(false), 2000);
        } catch (err) {
            console.error("Failed to copy:", err);
        }
    };

    const downloadReport = () => {
        const report = {
            job,
            attempts,
            events,
            exportedAt: new Date().toISOString()
        };
        const blob = new Blob([JSON.stringify(report, null, 2)], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `repodoctor-${job?.repoName}-${jobId.slice(0, 8)}.json`;
        a.click();
        URL.revokeObjectURL(url);
    };

    const copyDiff = async () => {
        if (!content || viewMode !== "diff") return;
        try {
            await navigator.clipboard.writeText(content);
            setCopyDiffSuccess(true);
            setTimeout(() => setCopyDiffSuccess(false), 2000);
        } catch (err) {
            console.error("Failed to copy diff:", err);
        }
    };

    // Initiate GitHub OAuth flow for PR creation
    const createPullRequest = async () => {
        if (!job || !finalAttempt) return;

        // Find the successful attempt (last one with SUCCESS status, or the final attempt)
        const successfulAttempt = [...attempts].reverse().find(a => a.status === "SUCCESS") || finalAttempt;

        if (successfulAttempt.attemptNumber === 0) {
            setPrError("Cannot create PR from baseline - no fix was applied");
            return;
        }

        setCreatingPR(true);
        setPrError(null);

        try {
            // Get the authorization URL from backend
            const response = await fetch(
                `${API_URL}/api/github/authorize?jobId=${jobId}&attemptNumber=${successfulAttempt.attemptNumber}`
            );

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.error || "Failed to start authorization");
            }

            const data = await response.json();

            // Open GitHub OAuth in a new tab
            // The callback will handle PR creation and redirect to the PR URL in that tab
            window.open(data.authUrl, "_blank");

            // Reset loading state since the flow continues in new tab
            setCreatingPR(false);

        } catch (error) {
            console.error("Failed to create PR:", error);
            setPrError(error instanceof Error ? error.message : "Failed to create PR");
            setCreatingPR(false);
        }
    };

    const downloadDiff = () => {
        if (!content || viewMode !== "diff") return;
        const blob = new Blob([content], { type: "text/plain" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `${job?.repoName || "patch"}-attempt-${selectedAttempt}.patch`;
        a.click();
        URL.revokeObjectURL(url);
    };

    // Check if tests actually ran
    const baselineAttempt = attempts.find(a => a.attemptNumber === 0);
    const finalAttempt = attempts.length > 0 ? attempts[attempts.length - 1] : null;
    const testsActuallyRan = (baselineAttempt?.testsRun ?? 0) > 0 || (finalAttempt?.testsRun ?? 0) > 0;

    if (loading) {
        return (
            <div className="min-h-screen flex items-center justify-center">
                <div className="spinner"></div>
            </div>
        );
    }

    return (
        <div className="min-h-screen p-6">
            {/* Header */}
            <header className="max-w-7xl mx-auto mb-8">
                <Link href="/" className="inline-flex items-center gap-2 text-gray-400 hover:text-white mb-4 transition-colors">
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
                    </svg>
                    Back to Home
                </Link>

                <div className="flex items-center justify-between flex-wrap gap-4">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-3 flex-wrap">
                            <span className="bg-gradient-to-r from-blue-500 to-purple-500 bg-clip-text text-transparent">
                                {job?.repoName || "Loading..."}
                            </span>
                            {job && (
                                <span className={`status-badge ${getStatusClass(job.status)}`}>
                                    {job.status}
                                </span>
                            )}
                        </h1>
                        <div className="flex items-center gap-4 mt-2 text-sm text-gray-500 flex-wrap">
                            {job?.buildTool && <span>🔧 {job.buildTool}</span>}
                            <span>📊 {attempts.length} attempt(s)</span>
                            {connected ? (
                                <span className="flex items-center gap-1">
                                    <span className="w-2 h-2 bg-green-500 rounded-full animate-pulse"></span>
                                    Live
                                </span>
                            ) : (
                                <span className="text-gray-600">Disconnected</span>
                            )}
                        </div>
                    </div>

                    {/* Utility Buttons */}
                    <div className="flex items-center gap-2">
                        <button
                            onClick={copyJobLink}
                            className="p-2 text-gray-400 hover:text-white hover:bg-gray-800 rounded-lg transition-all"
                            title="Copy job link"
                        >
                            {copySuccess ? "✓" : "🔗"}
                        </button>
                        <button
                            onClick={downloadReport}
                            className="p-2 text-gray-400 hover:text-white hover:bg-gray-800 rounded-lg transition-all"
                            title="Download report"
                        >
                            📥
                        </button>
                        {!job?.llmConfigured && (
                            <div className="px-4 py-2 bg-yellow-500/10 border border-yellow-500/30 rounded-lg text-sm text-yellow-200">
                                ⚠️ Gemini API not configured
                            </div>
                        )}
                    </div>
                </div>

                {/* Keyboard shortcuts hint */}
                <div className="mt-3 flex items-center gap-4 text-xs text-gray-600">
                    <span className="flex items-center gap-1">
                        <kbd className="kbd">L</kbd> Logs
                    </span>
                    <span className="flex items-center gap-1">
                        <kbd className="kbd">D</kbd> Diff
                    </span>
                    <span className="flex items-center gap-1">
                        <kbd className="kbd">A</kbd> AI Insights
                    </span>
                    <span className="flex items-center gap-1">
                        <kbd className="kbd">↑↓</kbd> Navigate
                    </span>
                </div>
            </header>

            <div className="max-w-7xl mx-auto grid grid-cols-12 gap-6">
                {/* Left: Attempt Timeline */}
                <div className="col-span-12 lg:col-span-4">
                    <div className="card">
                        <h2 className="text-lg font-semibold mb-4">Attempt Timeline</h2>

                        <div className="timeline">
                            {attempts.map((attempt) => (
                                <div
                                    key={attempt.attemptNumber}
                                    className={`timeline-item ${getTimelineClass(attempt)} cursor-pointer transition-all ${selectedAttempt === attempt.attemptNumber
                                        ? "ring-1 ring-blue-500 rounded-lg bg-blue-500/10 p-3 -ml-3"
                                        : "p-3 -ml-3 hover:bg-gray-800/50 rounded-lg"
                                        }`}
                                    onClick={() => setSelectedAttempt(attempt.attemptNumber)}
                                >
                                    <div className="flex items-center justify-between mb-1 flex-wrap gap-2">
                                        <span className="font-medium">
                                            {attempt.attemptNumber === 0 ? "Baseline" : `Attempt ${attempt.attemptNumber}`}
                                        </span>
                                        <div className="flex items-center gap-2">
                                            {attempt.riskLevel && (
                                                <span className={`px-2 py-0.5 text-xs rounded-full ${getRiskLevelColor(attempt.riskLevel)}`}>
                                                    {attempt.riskLevel}
                                                </span>
                                            )}
                                            <span className={`status-badge text-xs ${getStatusClass(attempt.status)}`}>
                                                {attempt.status}
                                            </span>
                                        </div>
                                    </div>

                                    {/* Stats row */}
                                    <div className="flex items-center gap-3 text-sm text-gray-400 mt-2">
                                        {attempt.testsRun !== null && attempt.testsRun > 0 ? (
                                            <span>🧪 {attempt.testsPassed}✓ / {attempt.testsFailed}✗ of {attempt.testsRun}</span>
                                        ) : (
                                            <span className="text-gray-500">🧪 No tests ran</span>
                                        )}
                                        {attempt.exitCode !== null && attempt.exitCode !== 0 && (
                                            <span className="text-red-400">Exit: {attempt.exitCode}</span>
                                        )}
                                    </div>

                                    {/* Duration */}
                                    <div className="text-xs text-gray-500 mt-1">
                                        ⏱️ {formatDuration(attempt.startedAt, attempt.completedAt)}
                                    </div>

                                    {attempt.errorMessage && (
                                        <div className="text-xs text-red-400 mt-2 bg-red-500/10 p-2 rounded border border-red-500/20">
                                            {attempt.errorMessage}
                                        </div>
                                    )}

                                    {/* AI Explanation */}
                                    {attempt.explanation && (
                                        <div className="mt-3">
                                            <div className="flex items-center gap-2 text-xs text-blue-400 mb-1">
                                                <span>✨</span>
                                                <span>Gemini Analysis</span>
                                            </div>
                                            <p className={`text-sm text-gray-400 ${expandedExplanation === attempt.attemptNumber ? "" : "line-clamp-2"
                                                }`}>
                                                {attempt.explanation}
                                            </p>
                                            {attempt.explanation.length > 100 && (
                                                <button
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        setExpandedExplanation(
                                                            expandedExplanation === attempt.attemptNumber
                                                                ? null
                                                                : attempt.attemptNumber
                                                        );
                                                    }}
                                                    className="text-xs text-blue-400 hover:text-blue-300 mt-1"
                                                >
                                                    {expandedExplanation === attempt.attemptNumber ? "Show less" : "Read more →"}
                                                </button>
                                            )}

                                            {/* Confidence Notes */}
                                            {attempt.confidenceNotes && expandedExplanation === attempt.attemptNumber && (
                                                <div className="mt-2 p-2 bg-gray-800/50 rounded text-xs text-gray-400 border-l-2 border-blue-500">
                                                    <span className="text-gray-500 block mb-1">💭 Confidence Notes:</span>
                                                    {attempt.confidenceNotes}
                                                </div>
                                            )}
                                        </div>
                                    )}
                                </div>
                            ))}

                            {attempts.length === 0 && (
                                <div className="text-center py-8">
                                    <div className="spinner mx-auto mb-3"></div>
                                    <div className="text-gray-500">Waiting for attempts...</div>
                                    <div className="text-xs text-gray-600 mt-2">
                                        RepoDoctor is setting up the build environment
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Events */}
                    <div className="card mt-6">
                        <h2 className="text-lg font-semibold mb-4">Events</h2>
                        <div className="space-y-2 max-h-64 overflow-y-auto">
                            {events.map((event, i) => (
                                <div key={i} className="text-sm p-2 bg-gray-800/50 rounded flex items-center gap-2">
                                    <span className={`w-2 h-2 rounded-full ${event.type.includes("completed") ? "bg-green-500" :
                                        event.type.includes("failed") || event.type.includes("error") ? "bg-red-500" :
                                            event.type.includes("started") ? "bg-blue-500" :
                                                "bg-gray-500"
                                        }`}></span>
                                    <span className="text-blue-400">{event.type}</span>
                                    <span className="text-gray-600 text-xs ml-auto">
                                        {new Date(event.timestamp).toLocaleTimeString()}
                                    </span>
                                </div>
                            ))}
                            {events.length === 0 && (
                                <div className="text-center py-6">
                                    {job?.status === "COMPLETED" || job?.status === "FAILED" ? (
                                        <>
                                            <div className="text-gray-500">Job completed</div>
                                            <div className="text-xs text-gray-600 mt-1">
                                                All events have been processed
                                            </div>
                                        </>
                                    ) : job?.status === "RUNNING" || job?.status === "PENDING" ? (
                                        <>
                                            <div className="spinner-small mx-auto mb-2"></div>
                                            <div className="text-gray-500">Waiting for events...</div>
                                            <div className="text-xs text-gray-600 mt-2 space-y-1">
                                                <div>📥 Preparing workspace</div>
                                                <div>🔍 Running initial tests</div>
                                                <div>🧠 Analyzing with Gemini</div>
                                            </div>
                                        </>
                                    ) : (
                                        <div className="text-gray-500">No events yet...</div>
                                    )}
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Right: Content Viewer */}
                <div className="col-span-12 lg:col-span-8">
                    <div className="card h-full">
                        {/* Tabs */}
                        <div className="flex items-center gap-2 mb-4 flex-wrap">
                            <button
                                onClick={() => setViewMode("logs")}
                                className={`px-4 py-2 rounded-lg font-medium transition-colors ${viewMode === "logs" ? "bg-blue-500/20 text-blue-400" : "text-gray-400 hover:text-white hover:bg-gray-800"
                                    }`}
                            >
                                📋 Logs
                            </button>
                            <button
                                onClick={() => setViewMode("diff")}
                                className={`px-4 py-2 rounded-lg font-medium transition-colors ${viewMode === "diff" ? "bg-blue-500/20 text-blue-400" : "text-gray-400 hover:text-white hover:bg-gray-800"
                                    } ${selectedAttempt === 0 ? "opacity-50 cursor-not-allowed" : ""}`}
                                disabled={selectedAttempt === 0}
                            >
                                📝 Diff
                                {selectedAttempt !== 0 && currentAttempt?.explanation && (
                                    <span className="ml-2 px-1.5 py-0.5 text-xs bg-blue-500/30 rounded">
                                        AI
                                    </span>
                                )}
                            </button>
                            <button
                                onClick={() => setViewMode("ai")}
                                className={`px-4 py-2 rounded-lg font-medium transition-colors ${viewMode === "ai" ? "bg-purple-500/20 text-purple-400" : "text-gray-400 hover:text-white hover:bg-gray-800"
                                    }`}
                            >
                                🧠 AI Insights
                            </button>

                            {selectedAttempt !== null && (
                                <span className="ml-auto text-sm text-gray-500">
                                    {selectedAttempt === 0 ? "Baseline" : `Attempt ${selectedAttempt}`}
                                </span>
                            )}

                            {/* Copy and Download buttons for Diff */}
                            {viewMode === "diff" && content && selectedAttempt !== 0 && (
                                <div className="flex items-center gap-2 ml-4">
                                    <button
                                        onClick={copyDiff}
                                        className="px-3 py-1.5 text-sm bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors flex items-center gap-2"
                                        title="Copy patch to clipboard"
                                    >
                                        {copyDiffSuccess ? "✓ Copied!" : "📋 Copy"}
                                    </button>
                                    <button
                                        onClick={downloadDiff}
                                        className="px-3 py-1.5 text-sm bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors flex items-center gap-2"
                                        title="Download as .patch file"
                                    >
                                        ⬇️ Download
                                    </button>
                                </div>
                            )}
                        </div>

                        {/* Docker Error Helper */}
                        {currentAttempt?.exitCode === 125 && viewMode === "logs" && (
                            <div className="mb-4 p-4 bg-orange-500/10 border border-orange-500/30 rounded-lg">
                                <div className="flex items-start gap-3">
                                    <span className="text-2xl">🐳</span>
                                    <div className="flex-1">
                                        <h4 className="font-semibold text-orange-400">Docker Configuration Required</h4>
                                        <p className="text-sm text-gray-300 mt-1">
                                            The workspace directory is not shared with Docker Desktop.
                                        </p>
                                        <div className="mt-3 p-3 bg-gray-900/50 rounded text-sm">
                                            <div className="font-medium text-gray-300 mb-2">Quick Fix:</div>
                                            <ol className="list-decimal list-inside space-y-1 text-gray-400">
                                                <li>Open <span className="text-white">Docker Desktop</span> → Preferences</li>
                                                <li>Go to <span className="text-white">Resources</span> → <span className="text-white">File Sharing</span></li>
                                                <li>Add the workspace path or parent directory</li>
                                                <li>Click <span className="text-white">Apply &amp; Restart</span></li>
                                            </ol>
                                        </div>
                                        <div className="mt-3 flex gap-2 flex-wrap">
                                            <a
                                                href="https://docs.docker.com/desktop/settings/mac/#file-sharing"
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className="px-3 py-1.5 text-sm bg-gray-800 hover:bg-gray-700 rounded transition-colors"
                                            >
                                                📖 Docker Docs
                                            </a>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        )}

                        {/* Content */}
                        {viewMode === "ai" ? (
                            <div className="ai-insights-container">
                                {currentAttempt ? (
                                    <div className="space-y-4">
                                        {/* Model Info Card */}
                                        <div className="ai-card">
                                            <h3 className="text-sm font-semibold text-gray-400 mb-3 flex items-center gap-2">
                                                <span>🤖</span> Model Configuration
                                            </h3>
                                            <div className="flex items-center gap-3 flex-wrap">
                                                <span className="px-3 py-1 bg-purple-500/20 text-purple-400 rounded-full text-sm">
                                                    gemini-3-flash
                                                </span>
                                                <span className="text-sm text-gray-500">
                                                    Thinking Level: <span className="text-white">Low</span> (optimized for patch generation)
                                                </span>
                                            </div>
                                        </div>

                                        {/* Analysis Card */}
                                        {currentAttempt.explanation && (
                                            <div className="ai-card">
                                                <h3 className="text-sm font-semibold text-gray-400 mb-3 flex items-center gap-2">
                                                    <span>🔍</span> Failure Analysis
                                                </h3>
                                                <div className="space-y-3">
                                                    {currentAttempt.exitCode && (
                                                        <div className="flex items-start gap-2">
                                                            <span className="text-gray-500 text-sm min-w-[100px]">Exit Code:</span>
                                                            <div>
                                                                <span className="text-red-400 font-mono">{currentAttempt.exitCode}</span>
                                                                <span className="text-gray-400 text-sm ml-2">
                                                                    ({getExitCodeExplanation(currentAttempt.exitCode).title})
                                                                </span>
                                                            </div>
                                                        </div>
                                                    )}
                                                    <div>
                                                        <span className="text-gray-500 text-sm block mb-2">AI Assessment:</span>
                                                        <p className="text-gray-300 leading-relaxed">
                                                            {currentAttempt.explanation}
                                                        </p>
                                                    </div>
                                                </div>
                                            </div>
                                        )}

                                        {/* Patch Strategy Card */}
                                        {(currentAttempt.riskLevel || currentAttempt.confidenceNotes) && (
                                            <div className="ai-card">
                                                <h3 className="text-sm font-semibold text-gray-400 mb-3 flex items-center gap-2">
                                                    <span>🔧</span> Patch Strategy
                                                </h3>
                                                <div className="space-y-3">
                                                    {currentAttempt.riskLevel && (
                                                        <div className="flex items-center gap-2">
                                                            <span className="text-gray-500 text-sm">Risk Level:</span>
                                                            <span className={`px-3 py-1 rounded-full text-sm ${getRiskLevelColor(currentAttempt.riskLevel)}`}>
                                                                {currentAttempt.riskLevel}
                                                            </span>
                                                            <span className="text-xs text-gray-500">
                                                                {currentAttempt.riskLevel === "LOW" && "- Safe, minimal change"}
                                                                {currentAttempt.riskLevel === "MEDIUM" && "- Moderate complexity"}
                                                                {currentAttempt.riskLevel === "HIGH" && "- Review carefully"}
                                                            </span>
                                                        </div>
                                                    )}
                                                    {currentAttempt.confidenceNotes && (
                                                        <div>
                                                            <span className="text-gray-500 text-sm block mb-2">💭 Confidence Notes:</span>
                                                            <p className="text-gray-400 text-sm leading-relaxed p-3 bg-gray-900/50 rounded border-l-2 border-purple-500">
                                                                {currentAttempt.confidenceNotes}
                                                            </p>
                                                        </div>
                                                    )}
                                                </div>
                                            </div>
                                        )}

                                        {/* Learning from Previous Card */}
                                        {currentAttempt.attemptNumber > 1 && (
                                            <div className="ai-card">
                                                <h3 className="text-sm font-semibold text-gray-400 mb-3 flex items-center gap-2">
                                                    <span>📚</span> Learning from Previous Attempts
                                                </h3>
                                                <div className="space-y-2">
                                                    {attempts
                                                        .filter(a => a.attemptNumber < currentAttempt.attemptNumber && a.attemptNumber > 0)
                                                        .map(prior => (
                                                            <div key={prior.attemptNumber} className="p-2 bg-gray-900/30 rounded text-sm">
                                                                <span className="text-gray-400">Attempt {prior.attemptNumber}:</span>
                                                                <span className={`ml-2 ${prior.status === "SUCCESS" ? "text-green-400" : "text-red-400"}`}>
                                                                    {prior.status}
                                                                </span>
                                                                {prior.errorMessage && (
                                                                    <div className="text-xs text-gray-500 mt-1">{prior.errorMessage}</div>
                                                                )}
                                                            </div>
                                                        ))}
                                                </div>
                                            </div>
                                        )}

                                        {/* Baseline Analysis */}
                                        {currentAttempt.attemptNumber === 0 && !currentAttempt.explanation && (
                                            <div className="text-center py-12 text-gray-500">
                                                <div className="text-4xl mb-3">🧪</div>
                                                <div>Baseline run - no AI analysis</div>
                                                <div className="text-sm mt-1">
                                                    Select an attempt to see Gemini&apos;s analysis
                                                </div>
                                            </div>
                                        )}

                                        {/* Baseline has analysis now */}
                                        {currentAttempt.attemptNumber === 0 && currentAttempt.explanation && (
                                            <div className="ai-card">
                                                <h3 className="text-sm font-semibold text-gray-400 mb-3 flex items-center gap-2">
                                                    <span>🔍</span> Baseline Failure Analysis
                                                </h3>
                                                <div className="space-y-3">
                                                    <div>
                                                        <span className="text-gray-500 text-sm block mb-2">Quick Assessment:</span>
                                                        <p className="text-gray-300 leading-relaxed">
                                                            {currentAttempt.explanation}
                                                        </p>
                                                    </div>
                                                    <div className="text-xs text-gray-500 italic mt-2">
                                                        ℹ️ This analysis was generated locally without using Gemini API
                                                    </div>
                                                </div>
                                            </div>
                                        )}

                                        {/* No analysis available */}
                                        {currentAttempt.attemptNumber > 0 && !currentAttempt.explanation && (
                                            <div className="text-center py-12 text-gray-500">
                                                <div className="text-4xl mb-3">🤔</div>
                                                <div>No AI analysis available for this attempt</div>
                                                <div className="text-sm mt-1">
                                                    The attempt may have failed before AI analysis could complete
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                ) : (
                                    <div className="text-center py-20 text-gray-500">
                                        Select an attempt to view AI insights
                                    </div>
                                )}
                            </div>
                        ) : (
                            <div className="code-viewer min-h-[400px]">
                                {content ? (
                                    viewMode === "diff" ? formatDiff(content) : (
                                        <pre className="whitespace-pre-wrap">{content}</pre>
                                    )
                                ) : (
                                    <div className="text-gray-500 text-center py-20">
                                        {selectedAttempt === null
                                            ? "Select an attempt to view details"
                                            : viewMode === "diff" && selectedAttempt === 0
                                                ? "Baseline has no patch - select a later attempt to see AI-generated patches"
                                                : (
                                                    <div>
                                                        <div className="spinner mx-auto mb-3"></div>
                                                        Loading {viewMode}...
                                                    </div>
                                                )}
                                    </div>
                                )}
                            </div>
                        )}
                    </div>
                </div>
            </div>

            {/* Summary Section */}
            {(job?.status === "COMPLETED" || job?.status === "FAILED") && (
                <div className="max-w-7xl mx-auto mt-8">
                    <div className={`card ${job.status === "COMPLETED" ? "border-green-500/30" : "border-red-500/30"}`}>
                        <div className="flex items-start justify-between gap-4 flex-wrap">
                            <div>
                                <h2 className="text-lg font-semibold mb-2 flex items-center gap-2">
                                    {job.status === "COMPLETED" ? (
                                        <>
                                            <span className="text-2xl">✅</span>
                                            <span className="text-green-400">Success</span>
                                        </>
                                    ) : (
                                        <>
                                            <span className="text-2xl">❌</span>
                                            <span className="text-red-400">Failed</span>
                                        </>
                                    )}
                                </h2>
                                <p className="text-gray-300">
                                    {job.errorMessage || (job.status === "COMPLETED" ? "All tests pass!" : "See attempt details for errors.")}
                                </p>
                            </div>

                            {/* Action buttons */}
                            <div className="flex gap-2 flex-wrap">
                                <button
                                    onClick={downloadReport}
                                    className="px-4 py-2 bg-gray-800 hover:bg-gray-700 rounded-lg text-sm transition-colors"
                                >
                                    📥 Download Report
                                </button>

                                {/* Create PR Button - only show for successful completions with a GitHub repo */}
                                {githubEnabled && job.status === "COMPLETED" && job.repoUrl?.includes("github.com") && (
                                    <button
                                        onClick={createPullRequest}
                                        disabled={creatingPR}
                                        className={`px-4 py-2 rounded-lg text-sm transition-colors flex items-center gap-2 ${creatingPR
                                            ? "bg-gray-700 text-gray-400 cursor-not-allowed"
                                            : "bg-green-600 hover:bg-green-500 text-white"
                                            }`}
                                    >
                                        {creatingPR ? (
                                            <>
                                                <span className="spinner-small"></span>
                                                Creating PR...
                                            </>
                                        ) : (
                                            <>
                                                🚀 Create Pull Request
                                            </>
                                        )}
                                    </button>
                                )}
                            </div>

                            {/* PR Error message */}
                            {prError && (
                                <div className="mt-2 p-2 bg-red-500/10 border border-red-500/30 rounded text-sm text-red-400">
                                    ⚠️ {prError}
                                </div>
                            )}
                        </div>

                        {/* Test comparison or error explanation */}
                        {testsActuallyRan ? (
                            <div className="mt-6 grid grid-cols-1 md:grid-cols-2 gap-4">
                                <div className="p-4 bg-red-500/10 rounded-lg border border-red-500/20">
                                    <h3 className="font-medium text-red-400 mb-2">Before (Baseline)</h3>
                                    <div className="text-3xl font-bold text-red-500">
                                        {baselineAttempt?.testsFailed || 0} failing
                                    </div>
                                    <div className="text-sm text-gray-500">
                                        of {baselineAttempt?.testsRun || 0} tests
                                    </div>
                                </div>
                                <div className={`p-4 rounded-lg border ${finalAttempt?.testsFailed === 0
                                    ? "bg-green-500/10 border-green-500/20"
                                    : "bg-orange-500/10 border-orange-500/20"
                                    }`}>
                                    <h3 className={`font-medium mb-2 ${finalAttempt?.testsFailed === 0 ? "text-green-400" : "text-orange-400"
                                        }`}>
                                        After ({attempts.length - 1} Attempt{attempts.length > 2 ? "s" : ""})
                                    </h3>
                                    <div className={`text-3xl font-bold ${finalAttempt?.testsFailed === 0 ? "text-green-500" : "text-orange-500"
                                        }`}>
                                        {finalAttempt?.testsFailed || 0} failing
                                    </div>
                                    <div className="text-sm text-gray-500">
                                        of {finalAttempt?.testsRun || 0} tests
                                    </div>
                                    {baselineAttempt && finalAttempt &&
                                        (baselineAttempt.testsFailed || 0) > (finalAttempt.testsFailed || 0) && (
                                            <div className="mt-2 text-sm text-green-400">
                                                ✨ Fixed {(baselineAttempt.testsFailed || 0) - (finalAttempt.testsFailed || 0)} test(s)!
                                            </div>
                                        )}
                                </div>
                            </div>
                        ) : (
                            <div className="mt-6 p-4 bg-gray-800/50 rounded-lg">
                                <div className="flex items-start gap-3">
                                    <span className="text-2xl">⚠️</span>
                                    <div>
                                        <h4 className="font-medium text-yellow-400">No Tests Executed</h4>
                                        <p className="text-sm text-gray-400 mt-1">
                                            {getExitCodeExplanation(finalAttempt?.exitCode || null).description}
                                        </p>
                                        {finalAttempt?.exitCode && (
                                            <div className="mt-2 text-sm">
                                                <span className="text-gray-500">Exit code: </span>
                                                <span className="font-mono text-red-400">{finalAttempt.exitCode}</span>
                                            </div>
                                        )}
                                    </div>
                                </div>
                            </div>
                        )}

                        {/* Gemini Summary if available */}
                        {finalAttempt?.explanation && (
                            <div className="mt-6 p-4 bg-purple-500/10 rounded-lg border border-purple-500/20">
                                <h4 className="font-medium text-purple-400 mb-2 flex items-center gap-2">
                                    <span>🧠</span> Gemini&apos;s Final Assessment
                                </h4>
                                <p className="text-sm text-gray-300 leading-relaxed">
                                    {finalAttempt.explanation}
                                </p>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
