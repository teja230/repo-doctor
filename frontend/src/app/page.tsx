"use client";

import { useState, useRef, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";

export default function Home() {
  const router = useRouter();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [repoUrl, setRepoUrl] = useState("");
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [maxAttempts, setMaxAttempts] = useState(1);
  const [allowNetwork, setAllowNetwork] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [recentJobs, setRecentJobs] = useState<Array<{
    id: string;
    repoName: string;
    status: string;
    createdAt: string;
  }>>([]);
  const [statusFilter, setStatusFilter] = useState<string>("all");
  const [deletingJobId, setDeletingJobId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" } | null>(null);
  const [urlValidation, setUrlValidation] = useState<{ valid: boolean; message: string } | null>(null);
  const [sessionId, setSessionId] = useState<string>("");
  const [showAllJobs, setShowAllJobs] = useState(false);

  // Initialize session ID
  useEffect(() => {
    let sid = localStorage.getItem("repo_doctor_session_id");
    if (!sid) {
      if (typeof crypto !== 'undefined' && crypto.randomUUID) {
        sid = crypto.randomUUID();
      } else {
        // Fallback for older browsers
        sid = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
          var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
          return v.toString(16);
        });
      }
      localStorage.setItem("repo_doctor_session_id", sid!);
    }
    setSessionId(sid!);
  }, []);

  /**
   * Validates and sanitizes GitHub repository URLs.
   *
   * Checks for:
   * - Valid URL format
   * - GitHub.com domain (including www)
   * - Proper owner/repo path structure
   * - No embedded credentials
   * - No query params or fragments (potential injection)
   * - HTTPS protocol (security)
   */
  const validateGitHubUrl = (url: string): { valid: boolean; message: string; cleanUrl?: string } => {
    if (!url || !url.trim()) {
      return { valid: true, message: "" }; // Empty is OK (user might use ZIP instead)
    }

    const trimmedUrl = url.trim();

    // Check for embedded credentials (user:pass@)
    if (/@/.test(trimmedUrl) && /:.*@/.test(trimmedUrl)) {
      return { valid: false, message: "URLs with credentials are not allowed" };
    }

    // Check for query parameters or fragments (potential injection)
    if (/[?#]/.test(trimmedUrl)) {
      return { valid: false, message: "URL should not contain query parameters or fragments" };
    }

    // Try to parse as URL
    let parsed: URL;
    try {
      // Auto-add https:// if missing protocol
      const urlToParse = trimmedUrl.match(/^https?:\/\//) ? trimmedUrl : `https://${trimmedUrl}`;
      parsed = new URL(urlToParse);
    } catch {
      return { valid: false, message: "Invalid URL format" };
    }

    // Must be HTTPS (or HTTP which we'll upgrade)
    if (!["http:", "https:"].includes(parsed.protocol)) {
      return { valid: false, message: "Only HTTP/HTTPS URLs are supported" };
    }

    // Must be GitHub domain
    const hostname = parsed.hostname.toLowerCase();
    if (!["github.com", "www.github.com"].includes(hostname)) {
      return { valid: false, message: "Only GitHub repositories are supported" };
    }

    // Check path structure: must be /owner/repo
    let path = parsed.pathname;

    // Remove .git suffix if present
    if (path.endsWith(".git")) {
      path = path.slice(0, -4);
    }

    // Remove trailing slashes
    path = path.replace(/\/+$/, "");

    // Split path and validate
    const parts = path.split("/").filter(p => p.length > 0);

    if (parts.length < 2) {
      return { valid: false, message: "URL must include owner and repository (e.g., github.com/owner/repo)" };
    }

    if (parts.length > 2) {
      // Could be github.com/owner/repo/tree/branch - extract just owner/repo
      // This is a common copy-paste mistake
    }

    const owner = parts[0];
    const repo = parts[1];

    // Validate owner/repo names (GitHub rules: alphanumeric, hyphens, underscores)
    const validNamePattern = /^[a-zA-Z0-9_.-]+$/;

    if (!validNamePattern.test(owner)) {
      return { valid: false, message: "Invalid repository owner name" };
    }

    if (!validNamePattern.test(repo)) {
      return { valid: false, message: "Invalid repository name" };
    }

    // Reserved names that can't be repo names
    const reservedPaths = ["settings", "pulls", "issues", "actions", "projects", "wiki", "security", "pulse", "graphs"];
    if (reservedPaths.includes(repo.toLowerCase())) {
      return { valid: false, message: "URL appears to be a GitHub page, not a repository" };
    }

    // Build clean URL
    const cleanUrl = `https://github.com/${owner}/${repo}`;

    return { valid: true, message: "Valid GitHub repository", cleanUrl };
  };

  // Fetch recent jobs
  useEffect(() => {
    if (!sessionId) return; // Wait for session ID

    // Build URL with params
    const url = new URL('/api/jobs', window.location.origin);
    if (showAllJobs) {
      url.searchParams.append('all', 'true');
    }

    fetch(url.toString(), {
      cache: 'no-store',
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'Pragma': 'no-cache',
        'X-Session-Id': sessionId
      }
    })
      .then(res => res.json())
      .then(data => setRecentJobs(data))
      .catch(err => console.error("Failed to fetch recent jobs:", err));
  }, [sessionId, showAllJobs]);

  const handleDeleteJob = async (e: React.MouseEvent, jobId: string) => {
    e.preventDefault();
    e.stopPropagation();

    if (deletingJobId === jobId) {
      // Confirmed - delete
      try {
        const res = await fetch(`/api/jobs/${jobId}`, { method: "DELETE" });
        if (res.ok) {
          setRecentJobs(prev => prev.filter(j => j.id !== jobId));
          setToast({ message: "Job deleted successfully", type: "success" });
          setTimeout(() => setToast(null), 3000);
        } else {
          setToast({ message: "Failed to delete job", type: "error" });
          setTimeout(() => setToast(null), 3000);
        }
      } catch (err) {
        console.error("Failed to delete job:", err);
        setToast({ message: "Failed to delete job", type: "error" });
        setTimeout(() => setToast(null), 3000);
      }
      setDeletingJobId(null);
    } else {
      // First click - ask for confirmation
      setDeletingJobId(jobId);
      setTimeout(() => setDeletingJobId(null), 3000); // Auto-cancel after 3s
    }
  };

  const filteredJobs = recentJobs.filter(job => {
    if (statusFilter === "all") return true;
    if (statusFilter === "pending") return !["COMPLETED", "FAILED"].includes(job.status);
    if (statusFilter === "completed") return job.status === "COMPLETED";
    if (statusFilter === "failed") return job.status === "FAILED";
    return true;
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const formData = new FormData();

      if (selectedFile) {
        formData.append("repoZip", selectedFile);
      } else if (repoUrl) {
        // Validate and clean the URL
        const validation = validateGitHubUrl(repoUrl);
        if (!validation.valid) {
          setError(validation.message);
          setLoading(false);
          return;
        }
        formData.append("repoUrl", validation.cleanUrl || repoUrl);
      } else {
        setError("Please provide a GitHub URL or upload a ZIP file");
        setLoading(false);
        return;
      }

      formData.append("maxAttempts", maxAttempts.toString());
      formData.append("allowNetwork", allowNetwork.toString());

      const response = await fetch('/api/jobs', {
        method: "POST",
        body: formData,
        headers: {
          'X-Session-Id': sessionId
        }
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.error || "Failed to create job");
      }

      router.push(`/jobs/${data.jobId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "An error occurred");
      setLoading(false);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      setSelectedFile(file);
      setRepoUrl(""); // Clear URL if file is selected
    }
  };

  return (
    <main className="min-h-screen flex flex-col">
      {/* Hero Section */}
      <div className="flex-1 flex items-center justify-center p-8">
        <div className="w-full max-w-2xl">
          {/* Logo and Title */}
          <div className="text-center mb-12 fade-in">
            <div className="inline-flex items-center justify-center w-20 h-20 rounded-2xl bg-gradient-to-br from-blue-500 to-purple-600 mb-6 shadow-lg shadow-blue-500/30">
              <svg className="w-10 h-10 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <h1 className="text-4xl font-bold mb-3 bg-gradient-to-r from-white to-gray-400 bg-clip-text text-transparent">
              RepoDoctor
            </h1>
            <p className="text-gray-400 text-lg">
              Autonomously diagnose and fix failing tests with AI
            </p>
          </div>

          {/* Main Form Card */}
          <form onSubmit={handleSubmit} className="card-glass fade-in" style={{ animationDelay: "0.1s" }}>
            {/* Repository Input */}
            <div className="mb-6">
              <label className="block text-sm font-medium text-gray-300 mb-2">
                GitHub Repository URL
              </label>
              <input
                type="text"
                value={repoUrl}
                onChange={(e) => {
                  const value = e.target.value;
                  setRepoUrl(value);
                  setSelectedFile(null);

                  // Real-time validation (debounced effect via state)
                  if (value.trim()) {
                    const result = validateGitHubUrl(value);
                    setUrlValidation(result);
                  } else {
                    setUrlValidation(null);
                  }
                }}
                placeholder="https://github.com/username/repo"
                className={`input ${urlValidation
                  ? urlValidation.valid
                    ? "border-green-500/50 focus:border-green-500"
                    : "border-red-500/50 focus:border-red-500"
                  : ""
                  }`}
                disabled={!!selectedFile}
              />
              {/* Validation feedback */}
              {urlValidation && repoUrl && (
                <div className={`mt-2 text-sm flex items-center gap-2 ${urlValidation.valid ? "text-green-400" : "text-red-400"
                  }`}>
                  <span>{urlValidation.valid ? "✓" : "✕"}</span>
                  <span>{urlValidation.message}</span>
                </div>
              )}
            </div>

            <div className="relative flex items-center gap-4 mb-6">
              <div className="flex-1 h-px bg-gray-700"></div>
              <span className="text-gray-500 text-sm">OR</span>
              <div className="flex-1 h-px bg-gray-700"></div>
            </div>

            {/* File Upload */}
            <div className="mb-6">
              <label className="block text-sm font-medium text-gray-300 mb-2">
                Upload ZIP File
              </label>
              <div
                onClick={() => fileInputRef.current?.click()}
                className="border-2 border-dashed border-gray-600 rounded-lg p-8 text-center cursor-pointer hover:border-blue-500 transition-colors"
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".zip"
                  onChange={handleFileChange}
                  className="hidden"
                />
                {selectedFile ? (
                  <div className="flex items-center justify-center gap-3">
                    <svg className="w-8 h-8 text-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                    <span className="text-gray-300">{selectedFile.name}</span>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setSelectedFile(null);
                      }}
                      className="text-gray-500 hover:text-red-500"
                    >
                      ✕
                    </button>
                  </div>
                ) : (
                  <>
                    <svg className="w-12 h-12 text-gray-500 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                    </svg>
                    <p className="text-gray-400 text-sm">
                      Drop a ZIP file here or click to browse
                    </p>
                    <p className="text-gray-600 text-xs mt-1">
                      Max 25MB, 250 files
                    </p>
                  </>
                )}
              </div>
            </div>

            {/* Settings */}
            <div className="grid grid-cols-2 gap-6 mb-6">
              <div>
                <label className="block text-sm font-medium text-gray-300 mb-2">
                  Max Attempts
                </label>
                <div className="flex items-center gap-3">
                  <input
                    type="range"
                    min="1"
                    max="2"
                    value={maxAttempts}
                    onChange={(e) => setMaxAttempts(parseInt(e.target.value))}
                    className="flex-1 accent-blue-500"
                  />
                  <span className="w-8 text-center font-mono text-blue-400">{maxAttempts}</span>
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-300 mb-2">
                  Allow Network
                </label>
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => setAllowNetwork(!allowNetwork)}
                    className={`toggle ${allowNetwork ? "active" : ""}`}
                  />
                  <span className="text-sm text-gray-400">
                    {allowNetwork ? "Enabled" : "Disabled"}
                  </span>
                </div>
              </div>
            </div>

            {/* Warning for network */}
            {allowNetwork && (
              <div className="mb-6 p-3 bg-yellow-500/10 border border-yellow-500/30 rounded-lg">
                <div className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-yellow-500 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                  </svg>
                  <p className="text-sm text-yellow-200">
                    Network access allows dependency downloads but reduces sandbox security.
                  </p>
                </div>
              </div>
            )}

            {/* Error Message */}
            {error && (
              <div className="mb-6 p-3 bg-red-500/10 border border-red-500/30 rounded-lg">
                <p className="text-sm text-red-400">{error}</p>
              </div>
            )}

            {/* Submit Button */}
            <button
              type="submit"
              disabled={loading || (!repoUrl && !selectedFile) || (!!repoUrl && urlValidation !== null && !urlValidation.valid)}
              className="btn btn-primary w-full text-lg py-4"
            >
              {loading ? (
                <>
                  <div className="spinner"></div>
                  <span>Starting Analysis...</span>
                </>
              ) : (
                <>
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                  </svg>
                  <span>Run RepoDoctor</span>
                </>
              )}
            </button>
          </form>

          {/* Features */}
          <div className="grid grid-cols-4 gap-4 mt-8 fade-in" style={{ animationDelay: "0.2s" }}>
            {[
              { icon: "🔍", title: "Diagnose", desc: "AI-powered failure analysis" },
              { icon: "🔧", title: "Patch", desc: "Automatic code fixes" },
              { icon: "✅", title: "Verify", desc: "Run tests until green" },
              { icon: "🚀", title: "Create PR", desc: "Submit fix to GitHub" },
            ].map((feature, i) => (
              <div key={i} className="text-center p-4">
                <div className="text-3xl mb-2">{feature.icon}</div>
                <h3 className="font-semibold text-gray-200">{feature.title}</h3>
                <p className="text-sm text-gray-500">{feature.desc}</p>
              </div>
            ))}
          </div>

          {/* Recent Jobs */}
          {recentJobs.length > 0 && (
            <div className="mt-8 fade-in" style={{ animationDelay: "0.3s" }}>
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-4">
                  <h2 className="text-lg font-semibold text-gray-300">Recent Jobs</h2>
                </div>
                <div className="flex gap-2">
                  {["all", "pending", "completed", "failed"].map((filter) => (
                    <button
                      key={filter}
                      onClick={() => setStatusFilter(filter)}
                      className={`px-3 py-1 text-xs rounded-full capitalize transition-colors ${statusFilter === filter
                        ? "bg-blue-500/30 text-blue-400"
                        : "bg-gray-700/50 text-gray-400 hover:bg-gray-700"
                        }`}
                    >
                      {filter}
                    </button>
                  ))}
                </div>
              </div>
              <div className="space-y-2">
                {filteredJobs.length === 0 ? (
                  <p className="text-gray-500 text-center py-4">No {statusFilter} jobs</p>
                ) : (
                  filteredJobs.map((job) => (
                    <Link
                      key={job.id}
                      href={`/jobs/${job.id}`}
                      className="block p-3 bg-gray-800/50 hover:bg-gray-800 rounded-lg transition-colors group"
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-medium text-gray-200">{job.repoName}</span>
                        <div className="flex items-center gap-2">
                          <span className={`text-xs px-2 py-1 rounded-full ${job.status === "COMPLETED" ? "bg-green-500/20 text-green-400" :
                            job.status === "FAILED" ? "bg-red-500/20 text-red-400" :
                              "bg-blue-500/20 text-blue-400"
                            }`}>
                            {job.status}
                          </span>
                          <button
                            onClick={(e) => handleDeleteJob(e, job.id)}
                            className={`opacity-0 group-hover:opacity-100 px-2 py-1 text-xs rounded transition-all ${deletingJobId === job.id
                              ? "bg-red-500/30 text-red-400 opacity-100"
                              : "text-gray-500 hover:text-red-400"
                              }`}
                            title={deletingJobId === job.id ? "Click again to confirm" : "Delete job"}
                          >
                            {deletingJobId === job.id ? "Confirm?" : "✕"}
                          </button>
                        </div>
                      </div>
                      <div className="text-xs text-gray-500 mt-1">
                        {new Date(job.createdAt).toLocaleString()}
                      </div>
                    </Link>
                  ))
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Footer */}
      <footer className="text-center py-6 text-gray-600 text-sm">
        Powered by Gemini 3 with deep thinking
      </footer>

      {/* Toast Notification */}
      {toast && (
        <div className={`fixed bottom-4 right-4 px-4 py-3 rounded-lg shadow-lg ${toast.type === "success" ? "bg-green-500/20 border border-green-500/50 text-green-400" :
          "bg-red-500/20 border border-red-500/50 text-red-400"
          } animate-slide-up`}>
          <div className="flex items-center gap-2">
            <span>{toast.type === "success" ? "✓" : "✕"}</span>
            <span>{toast.message}</span>
          </div>
        </div>
      )}
    </main>
  );
}
