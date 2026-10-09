import type { NextConfig } from "next";

// The browser only ever talks to this origin; /api/* is proxied to the Spring Boot backend. Same origin means
// the session and CSRF cookies just work (no CORS, SameSite=Lax is satisfied).
// The Content-Security-Policy for pages needs a per-request nonce, so it is set in src/proxy.ts rather than here.
// 127.0.0.1 rather than "localhost": the backend listens on the IPv4 loopback address only, and "localhost" may resolve to ::1 first.
const backend = process.env.BACKEND_URL ?? "http://127.0.0.1:8080";

const nextConfig: NextConfig = {
  // Do not advertise the framework in every response.
  poweredByHeader: false,
  // The Docker image sets NEXT_OUTPUT=standalone so that it ships only the files the server uses (frontend/Dockerfile).
  // Without it the build is the ordinary one that `npm start` runs.
  output: process.env.NEXT_OUTPUT === "standalone" ? "standalone" : undefined,
  // The image optimizer (/_next/image) fetches the path it is given from this server and caches the result without
  // regard to who asked. Uploaded documents can be images, so it must never be pointed at /api: it may read the
  // pictures the application ships (the seal and the sign-in backdrop), and nothing else. (Remote images are off
  // because no remotePatterns are set.)
  images: {
    localPatterns: [
      { pathname: "/svec-logo.png", search: "" },
      { pathname: "/campus-login.webp", search: "" },
    ],
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
          // Nothing here is meant to be embedded in, or opened by, another site.
          { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
          { key: "Cross-Origin-Resource-Policy", value: "same-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
