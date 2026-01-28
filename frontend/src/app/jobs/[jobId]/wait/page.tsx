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
    const [showViewReportButton, setShowViewReportButton] = useState(false);
    const [timeWaiting, setTimeWaiting] = useState(0);
    const [simulatedProgress, setSimulatedProgress] = useState({ step: 0, label: "Starting" });
    const [receivedRealEvent, setReceivedRealEvent] = useState(false);
    const logEndRef = useRef<HTMLDivElement>(null);
    const startTimeRef = useRef<Date>(new Date());
    const minDisplayTimeRef = useRef<number>(3000);
    const eventSourceRef = useRef<EventSource | null>(null);
    const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
    const lastEventTimeRef = useRef<Date>(new Date());
    const simulationTimeoutRef = useRef<NodeJS.Timeout | null>(null);
    const viewReportTimeoutRef = useRef<NodeJS.Timeout | null>(null);
    const autoRedirectTimeoutRef = useRef<NodeJS.Timeout | null>(null);

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
                return `Analysis complete!`;
            case 'error':
                return `Error: ${event.data.message || 'Unknown error'}`;
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

    const getEventColor = (type: string): string => {
        if (type.includes('complete') || type.includes('success')) return 'status-success';
        if (type.includes('error') || type.includes('fail')) return 'status-error';
        if (type.includes('analyzing') || type.includes('patch')) return 'status-analyzing';
        if (type.includes('running') || type.includes('started')) return 'status-running';
        return 'status-pending';
    };

    const getProgress = (): { step: number; total: number; label: string } => {
        if (jobStatus === 'COMPLETED' || redirecting) return { step: 5, total: 5, label: 'Complete' };
        if (currentAttempt > 0) return { step: 3 + currentAttempt, total: 5, label: `Fixing (Attempt ${currentAttempt})` };
        if (buildTool) return { step: 2, total: 5, label: 'Running Tests' };
        if (jobStatus === 'RUNNING') return { step: 1, total: 5, label: 'Initializing' };
        // Use simulated progress when available and no real events yet
        if (!receivedRealEvent && simulatedProgress.step > 0) {
            return { step: simulatedProgress.step, total: 5, label: simulatedProgress.label };
        }
        return { step: 0, total: 5, label: 'Starting' };
    };

    // Fetch initial job data and populate logs
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

                    const initialLogs: LogEntry[] = [];

                    if (data.repoName) {
                        initialLogs.push({
                            timestamp: new Date(data.createdAt || Date.now()),
                            type: 'job_started',
                            message: `Job started: analyzing ${data.repoName}`,
                            color: 'status-running'
                        });
                    }

                    if (data.buildTool && data.buildTool !== 'UNKNOWN') {
                        initialLogs.push({
                            timestamp: new Date(data.createdAt || Date.now()),
                            type: 'build_tool_detected',
                            message: `Build tool detected: ${data.buildTool}`,
                            color: 'status-success'
                        });
                    }

                    if (data.attemptCount > 0) {
                        initialLogs.push({
                            timestamp: new Date(),
                            type: 'info',
                            message: `Running ${data.attemptCount} attempt(s)...`,
                            color: 'status-running'
                        });
                    }

                    if (initialLogs.length > 0) {
                        setLogs(initialLogs);
                    }

                    if (data.status === 'COMPLETED' || data.status === 'FAILED') {
                        const elapsed = Date.now() - startTimeRef.current.getTime();
                        const remaining = Math.max(0, minDisplayTimeRef.current - elapsed);

                        initialLogs.push({
                            timestamp: new Date(),
                            type: 'job_completed',
                            message: 'Analysis complete!',
                            color: 'status-success'
                        });
                        setLogs(initialLogs);
                        setRedirecting(true);

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

    // Timeout and simulation logic with realistic event sequence
    useEffect(() => {
        let isMounted = true;
        const simulationTimeouts: NodeJS.Timeout[] = [];

        // Track time waiting
        const timeInterval = setInterval(() => {
            if (isMounted && !redirecting) {
                setTimeWaiting(prev => prev + 1);
            }
        }, 1000);

        // After 5 seconds, start simulating if no real events received
        simulationTimeoutRef.current = setTimeout(() => {
            if (isMounted && !redirecting && !receivedRealEvent) {
                // Simulate CLONING status
                setJobStatus("CLONING");
                setSimulatedProgress({ step: 1, label: "Cloning Repository" });

                // Generic progress indicator (not lying about specific events)
                setLogs(prev => [...prev, {
                    timestamp: new Date(),
                    type: 'info',
                    message: 'Analysis in progress...',
                    color: 'status-running'
                }]);

                // Setting up environment (3s later)
                const t1 = setTimeout(() => {
                    if (isMounted && !redirecting && !receivedRealEvent) {
                        setJobStatus("RUNNING");
                        setSimulatedProgress({ step: 2, label: "Setting Up" });
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'info',
                            message: 'Setting up analysis environment...',
                            color: 'status-running'
                        }]);
                    }
                }, 3000);
                simulationTimeouts.push(t1);

                // Processing repository (6s later)
                const t2 = setTimeout(() => {
                    if (isMounted && !redirecting && !receivedRealEvent) {
                        setSimulatedProgress({ step: 2, label: "Processing" });
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'info',
                            message: 'Processing repository...',
                            color: 'status-running'
                        }]);
                    }
                }, 6000);
                simulationTimeouts.push(t2);

                // Running analysis (10s later)
                const t3 = setTimeout(() => {
                    if (isMounted && !redirecting && !receivedRealEvent) {
                        setSimulatedProgress({ step: 3, label: "Running Tests" });
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'info',
                            message: 'Running analysis... This may take a few moments.',
                            color: 'status-running'
                        }]);
                    }
                }, 10000);
                simulationTimeouts.push(t3);

                // AI analysis starting (14s later)
                const t4 = setTimeout(() => {
                    if (isMounted && !redirecting && !receivedRealEvent) {
                        setSimulatedProgress({ step: 3, label: "AI Analysis" });
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'info',
                            message: 'Gemini 3.0 analysis in progress...',
                            color: 'status-analyzing'
                        }]);
                    }
                }, 14000);
                simulationTimeouts.push(t4);

                // Still working (18s later)
                const t5 = setTimeout(() => {
                    if (isMounted && !redirecting && !receivedRealEvent) {
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'info',
                            message: 'Analysis is taking longer than usual...',
                            color: 'status-warning'
                        }]);
                    }
                }, 18000);
                simulationTimeouts.push(t5);

                // Proposing solutions (22s later)
                const t6 = setTimeout(() => {
                    if (isMounted && !redirecting && !receivedRealEvent) {
                        setSimulatedProgress({ step: 4, label: "Proposing Fixes" });
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'info',
                            message: 'Proposing improvements...',
                            color: 'status-analyzing'
                        }]);
                    }
                }, 22000);
                simulationTimeouts.push(t6);

                // Finalizing (26s later)
                const t7 = setTimeout(() => {
                    if (isMounted && !redirecting) {
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'info',
                            message: 'Finalizing analysis...',
                            color: 'status-running'
                        }]);
                    }
                }, 26000);
                simulationTimeouts.push(t7);
            }
        }, 15000);

        // After 30 seconds, show "View Report Now" button
        viewReportTimeoutRef.current = setTimeout(() => {
            if (isMounted && !redirecting) {
                setShowViewReportButton(true);
                setLogs(prev => [...prev, {
                    timestamp: new Date(),
                    type: 'info',
                    message: 'Analysis is taking longer than expected. You can view the report now or continue waiting.',
                    color: 'status-warning'
                }]);
            }
        }, 30000);

        // After 60 seconds, auto-redirect as a safety net
        autoRedirectTimeoutRef.current = setTimeout(() => {
            if (isMounted && !redirecting) {
                setLogs(prev => [...prev, {
                    timestamp: new Date(),
                    type: 'info',
                    message: 'Redirecting to report...',
                    color: 'status-success'
                }]);
                setRedirecting(true);
                setTimeout(() => {
                    router.push(`/jobs/${jobId}`);
                }, 2000);
            }
        }, 60000);

        return () => {
            isMounted = false;
            clearInterval(timeInterval);
            simulationTimeouts.forEach(timeout => clearTimeout(timeout));
            if (simulationTimeoutRef.current) {
                clearTimeout(simulationTimeoutRef.current);
            }
            if (viewReportTimeoutRef.current) {
                clearTimeout(viewReportTimeoutRef.current);
            }
            if (autoRedirectTimeoutRef.current) {
                clearTimeout(autoRedirectTimeoutRef.current);
            }
        };
    }, [jobId, router, redirecting, logs.length]);


    // SSE connection with reconnection logic
    useEffect(() => {
        let isMounted = true;

        const connectSSE = async () => {
            if (!isMounted) return;

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

                const isHealthy = await waitForBackendHealth(
                    (attempt, max, message) => {
                        if (isMounted) {
                            setReconnectAttempts(attempt);
                        }
                    },
                    15,
                    3000,
                    8000
                );

                if (!isHealthy) {
                    if (isMounted) {
                        setLogs(prev => [...prev, {
                            timestamp: new Date(),
                            type: 'connection_error',
                            message: 'Failed to connect to backend.',
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
                        message: 'Backend is ready! Connecting...',
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
                setConnected(true);
                setConnectionError(false);
                setReconnectAttempts(0);
            };

            eventSource.onmessage = (event) => {
                if (!isMounted) return;
                try {
                    const data = JSON.parse(event.data) as JobEvent;

                    // Track that we received a real event
                    lastEventTimeRef.current = new Date();
                    setReceivedRealEvent(true); // Mark that we got real data

                    // Cancel simulation timeouts since we're getting real data
                    if (simulationTimeoutRef.current) {
                        clearTimeout(simulationTimeoutRef.current);
                        simulationTimeoutRef.current = null;
                    }
                    if (viewReportTimeoutRef.current) {
                        clearTimeout(viewReportTimeoutRef.current);
                        viewReportTimeoutRef.current = null;
                    }

                    const message = formatEventMessage(data);
                    setLogs(prev => [...prev, {
                        timestamp: new Date(),
                        type: data.type,
                        message,
                        color: getEventColor(data.type)
                    }]);

                    if (data.type === 'job_started' && data.data.repoName) {
                        setRepoName(data.data.repoName as string);
                        setJobStatus('RUNNING');
                    }

                    if (data.type === 'build_tool_detected' && data.data.buildTool) {
                        setBuildTool(data.data.buildTool as string);
                    }

                    if (data.type === 'attempt_started' && typeof data.data.attemptNumber === 'number') {
                        setCurrentAttempt(data.data.attemptNumber);
                    }

                    if (data.type === 'job_completed') {
                        setJobStatus('COMPLETED');
                        setRedirecting(true);

                        const elapsed = Date.now() - startTimeRef.current.getTime();
                        const remaining = Math.max(0, minDisplayTimeRef.current - elapsed);

                        const countdownInterval = setInterval(() => {
                            setRedirectCountdown(prev => {
                                if (prev <= 1) {
                                    clearInterval(countdownInterval);
                                    return 0;
                                }
                                return prev - 1;
                            });
                        }, 1000);

                        setTimeout(() => {
                            if (isMounted) {
                                router.push(`/jobs/${jobId}`);
                            }
                        }, remaining + 2000);
                    }
                } catch (e) {
                    console.error("[SSE] Failed to parse event:", e);
                }
            };

            eventSource.onerror = () => {
                if (!isMounted) return;
                setConnected(false);
                setConnectionError(true);

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
            if (eventSourceRef.current) {
                eventSourceRef.current.close();
            }
            if (reconnectTimeoutRef.current) {
                clearTimeout(reconnectTimeoutRef.current);
            }
        };
    }, [jobId, router, redirecting]);

    useEffect(() => {
        logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [logs]);

    useEffect(() => {
        const interval = setInterval(() => {
            setSpinnerFrame(f => (f + 1) % SPINNER_FRAMES.length);
        }, 80);
        return () => clearInterval(interval);
    }, []);

    const progress = getProgress();

    return (
        <div className="min-h-screen bg-gradient-to-br from-gray-950 via-gray-900 to-gray-950 flex items-center justify-center p-4 relative overflow-hidden">
            {/* Animated background effects */}
            <div className="absolute inset-0 opacity-30">
                <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-blue-500/20 rounded-full blur-3xl animate-pulse"></div>
                <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-purple-500/20 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '1s' }}></div>
            </div>

            <div className="w-full max-w-4xl relative z-10">
                {/* Modern Header */}
                <div className="text-center mb-8 fade-in">
                    <div className="inline-flex items-center gap-3 mb-4">
                        <div className="relative">
                            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center shadow-lg shadow-blue-500/50">
                                <svg className="w-6 h-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z" />
                                </svg>
                            </div>
                            {connected && (
                                <div className="absolute -top-1 -right-1 w-4 h-4 bg-green-400 rounded-full border-2 border-gray-900 animate-pulse"></div>
                            )}
                        </div>
                        <div className="text-left">
                            <h1 className="text-2xl font-bold bg-gradient-to-r from-blue-400 via-purple-400 to-pink-400 bg-clip-text text-transparent">
                                RepoDoctor Analysis
                            </h1>
                            <p className="text-sm text-gray-500">{repoName || "Loading..."}</p>
                        </div>
                    </div>
                </div>

                {/* Main Card */}
                <div className="glass-card rounded-2xl border border-white/10 shadow-2xl overflow-hidden fade-in" style={{ animationDelay: "0.1s" }}>
                    {/* Status Bar */}
                    <div className="px-6 py-4 bg-gradient-to-r from-gray-900/50 to-gray-800/50 border-b border-white/5">
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-4">
                                <div className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${redirecting ? 'bg-green-500/20 text-green-400 animate-pulse' :
                                    jobStatus === 'RUNNING' ? 'bg-blue-500/20 text-blue-400' :
                                        'bg-gray-500/20 text-gray-400'
                                    }`}>
                                    {redirecting ? '✓ COMPLETE' : jobStatus}
                                </div>
                                {buildTool && (
                                    <div className="flex items-center gap-2 text-xs text-gray-400">
                                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                                            <path d="M10.894 2.553a1 1 0 00-1.788 0l-7 14a1 1 0 001.169 1.409l5-1.429A1 1 0 009 15.571V11a1 1 0 112 0v4.571a1 1 0 00.725.962l5 1.428a1 1 0 001.17-1.408l-7-14z" />
                                        </svg>
                                        {buildTool}
                                    </div>
                                )}
                            </div>
                            <div className="flex items-center gap-2 text-xs text-gray-500">
                                {connected ? (
                                    <span className="flex items-center gap-1.5">
                                        <span className="w-2 h-2 bg-green-400 rounded-full animate-pulse"></span>
                                        Live
                                    </span>
                                ) : (
                                    <span>Connecting...</span>
                                )}
                            </div>
                        </div>
                    </div>

                    {/* Connection Banner */}
                    {connectionError && reconnecting && (
                        <div className="mx-6 mt-4 p-4 bg-yellow-500/10 border border-yellow-500/30 rounded-xl">
                            <div className="flex items-center gap-3">
                                <div className="spinner-small"></div>
                                <div className="flex-1">
                                    <div className="text-sm text-yellow-400 font-medium">Backend Waking Up</div>
                                    <div className="text-xs text-gray-400 mt-1">
                                        Free tier cold start (attempt {reconnectAttempts}/15)
                                    </div>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Log Stream */}
                    <div className="p-6">
                        <div className="space-y-2 min-h-[320px] max-h-[420px] overflow-y-auto custom-scrollbar">
                            {logs.length === 0 ? (
                                <div className="flex items-center justify-center h-[320px]">
                                    <div className="text-center">
                                        <div className="inline-block w-16 h-16 border-4 border-blue-500/30 border-t-blue-500 rounded-full animate-spin mb-4"></div>
                                        <p className="text-gray-400">Initializing workspace...</p>
                                    </div>
                                </div>
                            ) : (
                                logs.map((log, i) => (
                                    <div key={i} className="log-entry flex items-start gap-3 p-3 rounded-lg hover:bg-white/5 transition-colors">
                                        <div className={`w-2 h-2 rounded-full mt-1.5 flex-shrink-0 ${log.color === 'status-success' ? 'bg-green-400' :
                                            log.color === 'status-error' ? 'bg-red-400' :
                                                log.color === 'status-analyzing' ? 'bg-purple-400' :
                                                    log.color === 'status-running' ? 'bg-blue-400 animate-pulse' :
                                                        'bg-gray-500'
                                            }`}></div>
                                        <div className="flex-1 min-w-0">
                                            <p className={`text-sm ${log.color === 'status-success' ? 'text-green-400' :
                                                log.color === 'status-error' ? 'text-red-400' :
                                                    log.color === 'status-analyzing' ? 'text-purple-400' :
                                                        log.color === 'status-running' ? 'text-blue-400' :
                                                            'text-gray-300'
                                                }`}>
                                                {log.message}
                                            </p>
                                        </div>
                                        <span className="text-xs text-gray-600 flex-shrink-0">
                                            {log.timestamp.toLocaleTimeString('en-US', {
                                                hour12: false,
                                                hour: '2-digit',
                                                minute: '2-digit',
                                                second: '2-digit'
                                            })}
                                        </span>
                                    </div>
                                ))
                            )}
                            {!redirecting && jobStatus === 'RUNNING' && logs.length > 0 && (
                                <div className="flex items-center gap-3 p-3">
                                    <div className="w-2 h-2 bg-blue-400 rounded-full animate-pulse"></div>
                                    <span className="text-sm text-blue-400">{progress.label}...</span>
                                    <span className="text-cyan-400">{SPINNER_FRAMES[spinnerFrame]}</span>
                                </div>
                            )}
                            {redirecting && (
                                <div className="flex items-center gap-3 p-3 bg-green-500/10 rounded-lg">
                                    <div className="w-2 h-2 bg-green-400 rounded-full"></div>
                                    <span className="text-sm text-green-400 animate-pulse">
                                        Redirecting to results in {redirectCountdown}s...
                                    </span>
                                </div>
                            )}
                            <div ref={logEndRef} />
                        </div>
                    </div>

                    {/* Progress Footer */}
                    <div className="px-6 pb-6">
                        <div className="flex items-center justify-between text-xs text-gray-500 mb-3">
                            <span className="font-medium">{progress.label}</span>
                            <span>Step {progress.step}/{progress.total}</span>
                        </div>
                        <div className="relative w-full h-2 bg-gray-800 rounded-full overflow-hidden">
                            <div
                                className="absolute inset-0 bg-gradient-to-r from-blue-500 via-purple-500 to-pink-500 transition-all duration-700 ease-out"
                                style={{
                                    width: `${(progress.step / progress.total) * 100}%`,
                                    boxShadow: '0 0 20px rgba(59, 130, 246, 0.5)'
                                }}
                            />
                        </div>
                    </div>
                </div>

                {/* View Report Button / Skip Link */}
                {!redirecting && showViewReportButton && (
                    <div className="text-center mt-6 fade-in" style={{ animationDelay: "0.2s" }}>
                        <button
                            onClick={() => router.push(`/jobs/${jobId}`)}
                            className="px-6 py-3 bg-gradient-to-r from-blue-500 to-purple-600 hover:from-blue-600 hover:to-purple-700 text-white font-semibold rounded-lg shadow-lg shadow-blue-500/50 transform hover:scale-105 transition-all duration-200 inline-flex items-center gap-2"
                        >
                            <span>View Report Now</span>
                            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                            </svg>
                        </button>
                        <p className="text-xs text-gray-500 mt-3">
                            Analysis may still be running in the background
                        </p>
                    </div>
                )}
                {!redirecting && !showViewReportButton && logs.length > 3 && (
                    <div className="text-center mt-6 fade-in" style={{ animationDelay: "0.3s" }}>
                        <button
                            onClick={() => router.push(`/jobs/${jobId}`)}
                            className="text-sm text-gray-500 hover:text-gray-300 transition-colors inline-flex items-center gap-2"
                        >
                            <span>View detailed results</span>
                            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                            </svg>
                        </button>
                    </div>
                )}
                {!redirecting && timeWaiting > 10 && (
                    <div className="text-center mt-4 fade-in">
                        <p className="text-xs text-gray-600">
                            Waiting for {timeWaiting}s {timeWaiting < 30 ? '(auto-redirect at 60s)' : timeWaiting < 60 ? '(redirecting soon...)' : ''}
                        </p>
                    </div>
                )}

                {/* Footer */}
                <div className="text-center mt-6 text-xs text-gray-600 fade-in" style={{ animationDelay: "0.4s" }}>
                    <p>Powered by Gemini 3.0</p>
                </div>
            </div>
        </div>
    );
}
