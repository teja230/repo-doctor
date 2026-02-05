import type { Metadata } from "next";
import "./globals.css";

const siteUrl = "https://repodoctor.onrender.com";
const title = "RepoDoctor";
const description = "🩺: An autonomous build-fixing agent powered by Gemini 3. It automatically diagnoses build failures, generates patches using deep reasoning, and verifies fixes in a sandboxed diagnose-patch-run loop.";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: title,
    template: `%s | ${title}`,
  },
  description,
  openGraph: {
    title,
    description,
    url: siteUrl,
    siteName: title,
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title,
    description,
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="antialiased">
        {children}
      </body>
    </html>
  );
}
