/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  // API proxying is handled by middleware.ts which reads env vars at runtime
};

export default nextConfig;
