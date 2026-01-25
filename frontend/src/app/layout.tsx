import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "RepoDoctor - Autonomous Build Fixer",
  description: "AI-powered autonomous build and test fixing agent powered by Gemini 3",
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
