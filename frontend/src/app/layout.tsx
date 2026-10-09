import type { Metadata } from "next";
import localFont from "next/font/local";
import { headers } from "next/headers";
import { connection } from "next/server";
import { AuthProvider } from "@/components/auth/AuthProvider";
import { THEME_SCRIPT } from "@/components/ThemeToggle";
import "./globals.css";

// One family for the whole application (Montserrat, SIL OFL, see fonts/OFL.txt), self-hosted: the system runs inside the
// college network and never calls out to a font service.
const montserrat = localFont({
  variable: "--font-montserrat",
  display: "swap",
  src: [
    { path: "./fonts/Montserrat-Variable.woff2", weight: "100 900", style: "normal" },
    { path: "./fonts/Montserrat-Italic-Variable.woff2", weight: "100 900", style: "italic" },
  ],
});

export const metadata: Metadata = {
  title: "Faculty Self Appraisal | Sri Vasavi Engineering College",
  description: "Faculty Self Appraisal & Assessment Report, Sri Vasavi Engineering College (Autonomous), Tadepalligudem.",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Render per request, never at build time: the Content-Security-Policy nonce (src/proxy.ts) is different for every
  // request, and Next can only put it on its script tags while rendering for that request.
  await connection();
  const nonce = (await headers()).get("x-nonce") ?? undefined;
  return (
    // suppressHydrationWarning applies to this element's own attributes only: browser extensions add attributes to <html>
    // before React loads (for example data-*-nonce), which is not a mismatch in our markup.
    <html lang="en" data-scroll-behavior="smooth" suppressHydrationWarning className={`${montserrat.variable} h-full antialiased`}>
      <head>
        {/* Sets the saved (or the system's) colour theme before the first paint, so a dark page never flashes white. */}
        <script nonce={nonce} dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body className="min-h-full">
        <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded-md focus:bg-surface focus:px-3 focus:py-2">
          Skip to content
        </a>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
