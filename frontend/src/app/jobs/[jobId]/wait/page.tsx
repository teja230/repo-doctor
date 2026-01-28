"use client";

import { useEffect, useState, useRef, use } from "react";
import { useRouter } from "next/navigation";
import { checkBackendHealth, waitForBackendHealth } from "@/lib/backend-health";

const API_URL = "";

interface JobEvent {
    type: string;
    timestamp: string;
    data: Record<string, unknown>;
}

interface LogEntry {
    timestamp: Date;
    type: string;
    message: string;
    color: string;
}

// Spinner animation frames (braille patterns)
const SPINNER_FRAMES = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];

export default function WaitingPage({ params }: { params: Promise<{ jobId: string }> }) {
    const { jobId } = use(params);
    const router = useRouter();
    const [logs, setLogs] = useState<LogEntry[]>([]);
    const [jobStatus, setJobStatus] = useState<string>("PENDING");
    const [repoName, setRepoName] = useState<string>("");
    const [spinnerFrame, setSpinnerFrame] = useState(0);
    const [connected, setConnected] = useState(false);
    const [redirecting, setRedirecting] = useState(false);
    const [redirectCountdown, setRedirectCountdown] = useState(2);
    const [currentAttempt, setCurrentAttempt] = useState(0);
    const [buildTool, setBuildTool] = useState<string>("");
    const [connectionError, setConnectionError] = useState(false);
    const [reconnecting, setReconnecting] = useState(false);
    const [reconnectAttempts, setReconnectAttempts] = useState(0);
    const logEndRef = useRef<HTMLDivElement>(null);
    const startTimeRef = useRef<Date>(new Date());
    const minDisplayTimeRef = useRef<number>(3000); // Minimum 3 seconds display
    const eventSourceRef = useRef<EventSource | null>(null);
    const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);

    // Format event message for terminal display
    const formatEventMessage = (event: JobEvent): string => {
        switch (event.type) {
            case 'job_started':
                return `Job started: analyzing ${event.data.repoName || 'repository'}`;
            case 'attempt_started':
                const isBaseline = event.data.attemptNumber === 0;
                return `${isBaseline ? 'Baseline' : `Attempt ${event.data.attemptNumber}`} started`;
            case 'run_completed':
                const exitCode = event.data.exitCode as number;
                const testsRun = event.data.testsRun as number;
                const testsFailed = event.data.testsFailed as number;
                if (testsRun > 0) {
                    return `Tests: ${testsRun - testsFailed}✓ ${testsFailed}✗ (total: ${testsRun})`;
                }
                return `Build ${exitCode === 0 ? 'succeeded' : 'failed'} (exit: ${exitCode})`;
            case 'patch_proposed':
                return `AI patch generated (risk: ${event.data.riskLevel || 'unknown'})`;
            case 'patch_applied':
                return `Patch applied successfully`;
            case 'job_completed':
                return `✓✓✓ Analysis complete! Redirecting to results...`;
            case 'error':
                return `✗ Error: ${event.data.message || 'Unknown error'}`;
            case 'build_tool_detected':
                return `Build tool detected: ${event.data.buildTool || 'unknown'}`;
            case 'analyzing_with_llm':
                return `Analyzing with AI (Gemini 3.0)...`;
            case 'improvement_mode':
                return `No failing tests - analyzing for improvements...`;
            default:
                return `${event.type}`;
        }
    };

    // Get color class for event type
    const getEventColor = (type: string): string => {
        if (type.includes('complete') || type.includes('success')) return 'status-success';
        if (type.includes('error') || type.includes('fail')) return 'status-error';
        if (type.includes('analyzing') || type.includes('patch')) return 'status-analyzing';
        if (type.includes('running') || type.includes('started')) return 'status-running';
        return 'status-pending';
    };

    // Get progress indicator
    const getProgress = (): { step: number; total: number; label: string } => {
        if (jobStatus === 'COMPLETED' || redirecting) return { step: 5, total: 5, label: 'Complete' };
        if (currentAttempt > 0) return { step: 3 + currentAttempt, total: 5, label: `Attempt ${currentAttempt}` };
        if (buildTool) return { step: 2, total: 5, label: 'Running Tests' };
        if (jobStatus === 'RUNNING') return { step: 1, total: 5, label: 'Initializing' };
        return { step: 0, total: 5, label: 'Starting' };
    };

    // Fetch initial job data
    useEffect(() => {
        const fetchJob = async () => {
            try {
                const response = await fetch(`${API_URL}/api/jobs/${jobId}`, {
                    cache: 'no-store',
                    headers: {
                        'Cache-Control': 'no-cache, no-store, must-revalidate',
                        'Pragma': 'no-cache'
                    }
                });
                if (response.ok) {
                    const data = await response.json();
                    setRepoName(data.repoName || "repository");
                    setJobStatus(data.status);
                    if (data.buildTool) setBuildTool(data.buildTool);

                    // If job is already completed, redirect immediately (respecting min display time)
                    if (data.status === 'COMPLETED' || data.status === 'FAILED') {
                        const elapsed = Date.now() - startTimeRef.current.getTime();
                        const remaining = Math.max(0, minDisplayTimeRef.current - elapsed);
                        setTimeout(() => {
                            router.push(`/jobs/${jobId}`);
                        }, remaining);
                    }
                }
            } catch (error) {
                console.error("Failed to fetch job:", error);
                setLogs(prev => [...prev, {
                    timestamp: new Date(),
                    type: 'error',
                    message: `Failed to fetch job data: ${error instanceof Error ? error.message : 'Unknown error'}`,
                    color: 'status-error'
                }]);
            }
        };

        fetchJob();
    }, [jobId, router]);

    // SSE connection with reconnection logic
    useEffect(() => {
        let isMounted = true;

        const connectSSE = async () => {
            if (!isMounted) return;

            console.log(`[SSE] Connecting to: ${API_URL}/api/jobs/${jobId}/events`);

            // Check backend health before connecting
            const health = await checkBackendHealth();
            if (!health.healthy && isMounted) {
                setConnectionError(true);
                setReconnecting(true);
                setLogs(prev => [...prev, {
                    timestamp: new Date(),
                    type: 'connection_error',
                    message: 'Backend is waking up... Please wait.',
                    color: 'status-warning'
                }]);

                // Wait for backend to wake up
                const isHealthy = await waitForBackendHealth(
                    (attempt, max, message) => {
                        if (isMounted) {
                            setReconnectAttempts(attempt);
                            console.log(`[Health Check] ${message}`);
                        }
                    },
                    15, // max attempts
                    3000,
                    8000
                );

                if (!isHealthy) {
                    if (isMounted) {
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'connection_error',
                            message: 'Failed to connect to backend. Please refresh the page or try again later.',
                            color: 'status-error'
                        }]);
                        setReconnecting(false);
                    }
                    return;
                }

                if (isMounted) {
                    setLogs(prev => [...prev, {
                        timestamp: new Date(),
                        type: 'connection',
                        message: 'Backend is ready! Connecting to job stream...',
                        color: 'status-success'
                    }]);
                    setConnectionError(false);
                    setReconnecting(false);
                }
            }

            if (!isMounted) return;

            const eventSource = new EventSource(`${API_URL}/api/jobs/${jobId}/events`);
            eventSourceRef.current = eventSource;

            eventSource.onopen = () => {
                if (!isMounted) return;
                console.log("[SSE] Connection opened");
                setConnected(true);
                setConnectionError(false);
                setReconnectAttempts(0);
                setLogs(prev => [...prev, {
                    timestamp: new Date(),
                    type: 'connection',
                    message: 'Connected to job stream ✓',
                    color: 'status-success'
                }]);
            };

            eventSource.onmessage = (event) => {
                if (!isMounted) return;
                console.log("[SSE] Message received:", event.data);
                try {
                    const data = JSON.parse(event.data) as JobEvent;
                    console.log("[SSE] Parsed event:", data);

                    // Add to log stream
                    const message = formatEventMessage(data);
                    setLogs(prev => [...prev, {
                        timestamp: new Date(),
                        type: data.type,
                        message,
                        color: getEventColor(data.type)
                    }]);

                    // Update job status
                    if (data.type === 'job_started' && data.data.repoName) {
                        setRepoName(data.data.repoName as string);
                        setJobStatus('RUNNING');
                    }

                    // Update build tool
                    if (data.type === 'build_tool_detected' && data.data.buildTool) {
                        setBuildTool(data.data.buildTool as string);
                    }

                    // Update attempt number
                    if (data.type === 'attempt_started' && typeof data.data.attemptNumber === 'number') {
                        setCurrentAttempt(data.data.attemptNumber);
                    }

                    // Handle job completion
                    if (data.type === 'job_completed') {
                        setJobStatus('COMPLETED');
                        setRedirecting(true);

                        // Ensure minimum display time has elapsed
                        const elapsed = Date.now() - startTimeRef.current.getTime();
                        const remaining = Math.max(0, minDisplayTimeRef.current - elapsed);

                        // Start countdown
                        const countdownInterval = setInterval(() => {
                            setRedirectCountdown(prev => {
                                if (prev <= 1) {
                                    clearInterval(countdownInterval);
                                    return 0;
                                }
                                return prev - 1;
                            });
                        }, 1000);

                        // Redirect after countdown
                        setTimeout(() => {
                            if (isMounted) {
                                router.push(`/jobs/${jobId}`);
                            }
                        }, remaining + 2000); // +2 seconds for countdown
                    }
                } catch (e) {
                    console.error("[SSE] Failed to parse SSE event:", e);
                }
            };

            eventSource.onerror = (error) => {
                console.error("[SSE] Connection error:", error);
                if (!isMounted) return;

                setConnected(false);
                setConnectionError(true);
                setLogs(prev => [...prev, {
                    timestamp: new Date(),
                    type: 'connection_error',
                    message: 'Connection lost. Reconnecting...',
                    color: 'status-warning'
                }]);

                // Auto-reconnect after 3 seconds
                reconnectTimeoutRef.current = setTimeout(() => {
                    if (isMounted && !redirecting) {
                        eventSource.close();
                        connectSSE();
                    }
                }, 3000);
            };
        };

        connectSSE();

        return () => {
            isMounted = false;
            console.log("[SSE] Closing connection");
            if (eventSourceRef.current) {
                eventSourceRef.current.close();
            }
            if (reconnectTimeoutRef.current) {
                clearTimeout(reconnectTimeoutRef.current);
            }
        };
    }, [jobId, router, redirecting]);

    // Auto-scroll to bottom
    useEffect(() => {
        logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [logs]);

    // Spinner animation
    useEffect(() => {
        const interval = setInterval(() => {
            setSpinnerFrame(f => (f + 1) % SPINNER_FRAMES.length);
        }, 80);
        return () => clearInterval(interval);
    }, []);

    const progress = getProgress();

    return (
        <div className="min-h-screen bg-[#0a0a0a] flex items-center justify-center p-4">
            <div className="w-full max-w-5xl">
                {/* ASCII Art Header */}
                <div className="terminal-header text-center mb-6 fade-in">
                    <pre className="text-green-400 text-xs sm:text-sm leading-tight">
{`╔═══════════════════════════════════════════════════════╗
║        RepoDoctor Analysis in Progress               ║
╚═══════════════════════════════════════════════════════╝`}
                    </pre>
                </div>

                {/* Terminal Window */}
                <div className="terminal-window fade-in" style={{ animationDelay: "0.1s" }}>
                    {/* Terminal Title Bar */}
                    <div className="flex items-center justify-between mb-4 pb-3 border-b border-gray-800">
                        <div className="flex items-center gap-3">
                            <div className="flex items-center gap-1.5">
                                <div className="w-3 h-3 rounded-full bg-red-500/50"></div>
                                <div className="w-3 h-3 rounded-full bg-yellow-500/50"></div>
                                <div className="w-3 h-3 rounded-full bg-green-500/50"></div>
                            </div>
                            <span className="text-gray-500 text-sm">repodoctor — {repoName || "..."}</span>
                        </div>
                        <div className="flex items-center gap-3">
                            {connected ? (
                                <span className="flex items-center gap-1.5 text-xs text-green-400">
                                    <span className="w-2 h-2 bg-green-400 rounded-full animate-pulse"></span>
                                    LIVE
                                </span>
                            ) : (
                                <span className="text-xs text-gray-600">DISCONNECTED</span>
                            )}
                            <span className={`text-xs px-2 py-1 rounded ${
                                redirecting ? 'bg-green-500/20 text-green-400 animate-pulse' :
                                jobStatus === 'RUNNING' ? 'bg-blue-500/20 text-blue-400' :
                                'bg-gray-500/20 text-gray-400'
                            }`}>
                                {redirecting ? 'COMPLETE' : jobStatus}
                            </span>
                        </div>
                    </div>

                    {/* Connection Status Banner */}
                    {connectionError && reconnecting && (
                        <div className="mb-4 p-3 bg-yellow-500/10 border border-yellow-500/30 rounded">
                            <div className="flex items-center gap-2">
                                <div className="spinner-small"></div>
                                <div className="flex-1">
                                    <div className="text-sm text-yellow-400 font-medium">Backend Waking Up...</div>
                                    <div className="text-xs text-gray-400 mt-1">
                                        Render free tier sleeps after inactivity. Waking up (attempt {reconnectAttempts}/15)
                                    </div>
                                </div>
                            </div>
                        </div>
                    )}

                    {connectionError && !reconnecting && !connected && (
                        <div className="mb-4 p-3 bg-red-500/10 border border-red-500/30 rounded">
                            <div className="flex items-center gap-2">
                                <span className="text-red-400">⚠️</span>
                                <div className="flex-1">
                                    <div className="text-sm text-red-400 font-medium">Connection Failed</div>
                                    <div className="text-xs text-gray-400 mt-1">
                                        Unable to connect to backend. Please refresh the page or try again later.
                                    </div>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Command Prompt Header */}
                    <div className="mb-3">
                        <div className="flex items-center gap-2 text-cyan-400">
                            <span className="terminal-prompt">$</span>
                            <span>repodoctor analyze {repoName || "..."}</span>
                        </div>
                    </div>

                    {/* Log Stream */}
                    <div className="space-y-1 min-h-[300px] max-h-[400px] overflow-y-auto mb-4">
                        {logs.length === 0 ? (
                            <div className="flex items-center gap-2 text-gray-500">
                                <span className="text-cyan-400">{SPINNER_FRAMES[spinnerFrame]}</span>
                                <span>Initializing workspace...</span>
                            </div>
                        ) : (
                            logs.map((log, i) => (
                                <div key={i} className="terminal-log-line">
                                    <span className="terminal-timestamp">
                                        {log.timestamp.toLocaleTimeString('en-US', {
                                            hour12: false,
                                            hour: '2-digit',
                                            minute: '2-digit',
                                            second: '2-digit',
                                            fractionalSecondDigits: 3
                                        })}
                                    </span>
                                    <span className="terminal-prompt">{'>'}</span>
                                    <span className={log.color}>{log.message}</span>
                                </div>
                            ))
                        )}
                        {!redirecting && jobStatus === 'RUNNING' && (
                            <div className="flex items-center gap-2 text-cyan-400">
                                <span className="text-cyan-400">{SPINNER_FRAMES[spinnerFrame]}</span>
                                <span>Processing...</span>
                                <span className="terminal-cursor"></span>
                            </div>
                        )}
                        {redirecting && (
                            <div className="terminal-log-line">
                                <span className="terminal-timestamp">
                                    {new Date().toLocaleTimeString('en-US', {
                                        hour12: false,
                                        hour: '2-digit',
                                        minute: '2-digit',
                                        second: '2-digit',
                                        fractionalSecondDigits: 3
                                    })}
                                </span>
                                <span className="terminal-prompt">{'>'}</span>
                                <span className="status-success animate-pulse">
                                    Redirecting to results in {redirectCountdown}s...
                                </span>
                            </div>
                        )}
                        <div ref={logEndRef} />
                    </div>

                    {/* Progress Bar */}
                    <div className="mt-4 pt-4 border-t border-gray-800">
                        <div className="flex items-center justify-between text-xs text-gray-500 mb-2">
                            <span>{progress.label}</span>
                            <span>Step {progress.step}/{progress.total}</span>
                        </div>
                        <div className="w-full h-1.5 bg-gray-800 rounded-full overflow-hidden">
                            <div
                                className="h-full bg-gradient-to-r from-cyan-500 to-blue-500 transition-all duration-500 ease-out"
                                style={{ width: `${(progress.step / progress.total) * 100}%` }}
                            />
                        </div>
                        {buildTool && (
                            <div className="mt-2 text-xs text-gray-600">
                                🔧 Build tool: <span className="text-cyan-400">{buildTool}</span>
                            </div>
                        )}
                    </div>
                </div>

                {/* Skip Link (escape hatch) */}
                {!redirecting && logs.length > 3 && (
                    <div className="text-center mt-4 fade-in" style={{ animationDelay: "0.3s" }}>
                        <button
                            onClick={() => router.push(`/jobs/${jobId}`)}
                            className="text-sm text-gray-600 hover:text-gray-400 transition-colors"
                        >
                            View detailed results →
                        </button>
                    </div>
                )}

                {/* Info Footer */}
                <div className="text-center mt-6 text-xs text-gray-700 fade-in" style={{ animationDelay: "0.4s" }}>
                    <p>Analysis powered by Gemini 3.0</p>
                </div>
            </div>
        </div>
    );
}
