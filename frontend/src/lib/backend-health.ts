/**
 * Backend health check utilities for handling Render free tier cold starts
 */

const API_URL = "";

export interface HealthCheckResult {
    healthy: boolean;
    waking: boolean;
    error?: string;
}

/**
 * Check if backend is healthy
 * Returns true if healthy, false if down/waking
 */
export async function checkBackendHealth(): Promise<HealthCheckResult> {
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 10000); // 10s timeout

        const response = await fetch(`${API_URL}/api/health`, {
            method: 'GET',
            signal: controller.signal,
            cache: 'no-store',
        });

        clearTimeout(timeoutId);

        if (response.ok) {
            return { healthy: true, waking: false };
        }

        // 503 typically means server is starting up
        if (response.status === 503) {
            return { healthy: false, waking: true, error: 'Backend is waking up...' };
        }

        return { healthy: false, waking: false, error: `Backend returned ${response.status}` };
    } catch (error) {
        // Network error or timeout - backend is likely down
        if (error instanceof Error && error.name === 'AbortError') {
            return { healthy: false, waking: true, error: 'Backend is starting up (timeout)' };
        }

        return {
            healthy: false,
            waking: true, // Assume it's waking up rather than permanently down
            error: 'Cannot reach backend - it may be waking up...'
        };
    }
}

/**
 * Wait for backend to become healthy with retries
 * Returns true when healthy, false if max retries exceeded
 */
export async function waitForBackendHealth(
    onProgress?: (attempt: number, maxAttempts: number, message: string) => void,
    maxAttempts = 20,
    initialDelay = 3000,
    maxDelay = 10000
): Promise<boolean> {
    let attempt = 0;
    let delay = initialDelay;

    while (attempt < maxAttempts) {
        attempt++;

        if (onProgress) {
            onProgress(attempt, maxAttempts, `Attempt ${attempt}/${maxAttempts}...`);
        }

        const result = await checkBackendHealth();

        if (result.healthy) {
            if (onProgress) {
                onProgress(attempt, maxAttempts, 'Backend is ready! ✓');
            }
            return true;
        }

        if (attempt < maxAttempts) {
            if (onProgress) {
                onProgress(
                    attempt,
                    maxAttempts,
                    result.waking
                        ? `Backend is waking up... (${Math.round(delay / 1000)}s until next check)`
                        : `Connection failed, retrying in ${Math.round(delay / 1000)}s...`
                );
            }

            await new Promise(resolve => setTimeout(resolve, delay));

            // Exponential backoff, but cap at maxDelay
            delay = Math.min(delay * 1.2, maxDelay);
        }
    }

    if (onProgress) {
        onProgress(maxAttempts, maxAttempts, 'Failed to connect to backend');
    }

    return false;
}

/**
 * Ping backend to wake it up (fire and forget)
 */
export async function pingBackend(): Promise<void> {
    try {
        // Fire and forget - just wake up the backend
        fetch(`${API_URL}/api/health`, {
            method: 'GET',
            cache: 'no-store'
        }).catch(() => {
            // Ignore errors
        });
    } catch {
        // Ignore errors
    }
}
