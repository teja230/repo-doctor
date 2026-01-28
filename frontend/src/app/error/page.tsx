"use client";

import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { Suspense } from "react";

interface ErrorInfo {
    emoji: string;
    title: string;
    message: string;
    actionable: boolean;
    steps?: string[];
    learnMoreUrl?: string;
}

function getErrorInfo(type: string | null, message: string): ErrorInfo {
    switch (type) {
        case "NO_WRITE_ACCESS":
            return {
                emoji: "🔒",
                title: "Write Access Required",
                message: message || "You don't have permission to create branches in this repository.",
                actionable: true,
                steps: [
                    "You need write access to create pull requests directly",
                    "Option 1: Ask the repository owner to add you as a collaborator",
                    "Option 2: Fork the repository to your account first, then work from your fork",
                    "GitHub's standard workflow for contributing to public repos is through forks"
                ],
                learnMoreUrl: "https://docs.github.com/en/get-started/quickstart/fork-a-repo"
            };

        case "VALIDATION_ERROR":
            return {
                emoji: "⚠️",
                title: "Validation Error",
                message: message || "There was a problem validating your request.",
                actionable: false
            };

        case "API_ERROR":
            return {
                emoji: "🔌",
                title: "GitHub API Error",
                message: message || "There was a problem communicating with GitHub.",
                actionable: false
            };

        default:
            return {
                emoji: "😕",
                title: "Something went wrong",
                message: message || "An unknown error occurred",
                actionable: false
            };
    }
}

function ErrorContent() {
    const searchParams = useSearchParams();
    const type = searchParams.get("type");
    const rawMessage = searchParams.get("message") || "An unknown error occurred";

    const errorInfo = getErrorInfo(type, rawMessage);

    return (
        <div className="min-h-screen flex items-center justify-center p-6 bg-gradient-to-br from-gray-950 via-gray-900 to-purple-950/30">
            <div className="card glass-card max-w-2xl w-full">
                {/* Error Icon */}
                <div className="text-center mb-6">
                    <div className="text-6xl mb-4">{errorInfo.emoji}</div>
                    <h1 className="text-2xl font-bold text-red-400 mb-2">{errorInfo.title}</h1>
                </div>

                {/* Error Message */}
                <div className="bg-gray-800/50 rounded-lg p-4 mb-6 border border-gray-700">
                    <p className="text-gray-300 leading-relaxed">{errorInfo.message}</p>
                </div>

                {/* Actionable Steps */}
                {errorInfo.actionable && errorInfo.steps && (
                    <div className="mb-6">
                        <h2 className="text-lg font-semibold text-gray-200 mb-3">What you can do:</h2>
                        <div className="space-y-2">
                            {errorInfo.steps.map((step, index) => (
                                <div key={index} className="flex items-start gap-3 text-sm text-gray-300">
                                    <span className="text-blue-400 font-mono mt-0.5">{index + 1}.</span>
                                    <span>{step}</span>
                                </div>
                            ))}
                        </div>
                    </div>
                )}

                {/* Actions */}
                <div className="flex items-center gap-3 flex-wrap">
                    <Link
                        href="/"
                        className="px-6 py-3 bg-blue-600 hover:bg-blue-500 rounded-lg transition-colors font-medium"
                    >
                        ← Back to Home
                    </Link>

                    {errorInfo.learnMoreUrl && (
                        <a
                            href={errorInfo.learnMoreUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="px-6 py-3 bg-gray-700 hover:bg-gray-600 rounded-lg transition-colors font-medium"
                        >
                            📖 Learn More
                        </a>
                    )}
                </div>

                {/* Support Note */}
                <div className="mt-6 pt-6 border-t border-gray-700">
                    <p className="text-xs text-gray-500 text-center">
                        Need help? Check the{" "}
                        <a
                            href="https://github.com/anthropics/repo-doctor"
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-blue-400 hover:text-blue-300 underline"
                        >
                            documentation
                        </a>
                        {" "}or open an issue on GitHub.
                    </p>
                </div>
            </div>
        </div>
    );
}

export default function ErrorPage() {
    return (
        <Suspense fallback={
            <div className="min-h-screen flex items-center justify-center">
                <div className="spinner"></div>
            </div>
        }>
            <ErrorContent />
        </Suspense>
    );
}
