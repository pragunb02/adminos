import type { Metadata } from "next";
import "./globals.css";
import Providers from "@/components/Providers";

// Every page is rendered per request, so each gets its own CSP script nonce (AOS-618; src/proxy.ts).
// A prerendered page would carry no nonce, and the CSP would rightly block its scripts.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "AdminOS",
  description: "Your personal life admin agent",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-gray-50 text-gray-900 antialiased">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
