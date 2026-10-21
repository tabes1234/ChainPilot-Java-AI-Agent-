import type { NextConfig } from "next";

// Browser calls stay same-origin: /api and /uploads are proxied to the Java backend.
// Required because ModelConfigController has no @CrossOrigin and avatars are served from /uploads.
const backendUrl = process.env.CHAINPILOT_BACKEND_URL || "http://localhost:8065";

const nextConfig: NextConfig = {
  cacheComponents: false,
  partialPrefetching: false,
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${backendUrl}/api/:path*` },
      { source: "/uploads/:path*", destination: `${backendUrl}/uploads/:path*` },
    ];
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
