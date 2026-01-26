import { NextRequest, NextResponse } from 'next/server';

export function middleware(request: NextRequest) {
    // Only proxy /api/* requests
    if (request.nextUrl.pathname.startsWith('/api/')) {
        // Read backend URL at RUNTIME (not build time!)
        const backendUrl = process.env.API_BACKEND_URL || 'http://localhost:8080';

        // Log for debugging
        console.log(`[Middleware] Proxying ${request.nextUrl.pathname} to ${backendUrl}`);

        // Build the target URL
        const targetUrl = new URL(request.nextUrl.pathname + request.nextUrl.search, backendUrl);

        // Rewrite to backend
        return NextResponse.rewrite(targetUrl);
    }

    return NextResponse.next();
}

export const config = {
    // Match all /api/* paths
    matcher: '/api/:path*',
};
