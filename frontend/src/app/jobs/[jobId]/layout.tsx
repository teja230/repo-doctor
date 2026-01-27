import { ReactNode } from 'react';

// Force dynamic rendering for all pages in this route segment
// This prevents Next.js from caching the job detail page
export const dynamic = 'force-dynamic';
export const revalidate = 0;

export default function JobLayout({ children }: { children: ReactNode }) {
    return <>{children}</>;
}
