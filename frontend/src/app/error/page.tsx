"use client";

import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { Suspense } from "react";

function ErrorContent() {
    const searchParams = useSearchParams();
    const message = searchParams.get("message") || "An unknown error occurred";

    return (
        <div className="min-h-screen flex items-center justify-center p-6">
            <div className="card max-w-lg w-full text-center">
                <div className="text-6xl mb-4">😕</div>
                <h1 className="text-2xl font-bold text-red-400 mb-4">Something went wrong</h1>
                <p className="text-gray-300 mb-6">{message}</p>
                <Link
                    href="/"
                    className="inline-block px-6 py-3 bg-blue-600 hover:bg-blue-500 rounded-lg transition-colors"
                >
                    ← Back to Home
                </Link>
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
