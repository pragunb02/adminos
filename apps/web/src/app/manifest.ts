import type { MetadataRoute } from "next";

/** Lets AdminOS be added to the iPhone Home Screen, which iOS requires for Web Push (AOS-510). */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "AdminOS",
    short_name: "AdminOS",
    description: "Where your money goes, every day, week and month.",
    start_url: "/",
    scope: "/",
    display: "standalone",
    background_color: "#f8fafc",
    theme_color: "#1d4ed8",
    icons: [
      { src: "/icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png" },
    ],
  };
}
