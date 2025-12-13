import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /* config options here */
  reactCompiler: true,

  // Allow Server Actions from external domain (app.noslop.me)
  experimental: {
    serverActions: {
      allowedOrigins: [
        'app.noslop.me',
        'localhost:3000',
        // Also allow internal network addresses
        '*.lan:3000',
        '192.168.*:3000',
      ],
    },
  },

  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${process.env.NOSLOP_BACKEND_URL || 'http://localhost:8000'}/api/:path*`,
      },
      {
        source: '/auth/:path*',
        destination: `${process.env.NOSLOP_BACKEND_URL || 'http://localhost:8000'}/auth/:path*`,
      },
      {
        source: '/docs',
        destination: `${process.env.NOSLOP_BACKEND_URL || 'http://localhost:8000'}/docs`,
      },
      {
        source: '/openapi.json',
        destination: `${process.env.NOSLOP_BACKEND_URL || 'http://localhost:8000'}/openapi.json`,
      },
    ];
  },
};

export default nextConfig;
