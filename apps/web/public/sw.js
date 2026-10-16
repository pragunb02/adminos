/*
 * AdminOS service worker (AOS-510): shows Web Push notifications and opens the app on tap.
 * Nothing is cached here: AdminOS shows live money data, never a stale copy.
 */

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

/** The payload the API sends (WebPushSender.payload): { title, body, url, tag? }. */
function parse(event) {
  try {
    const data = event.data ? event.data.json() : {};
    return {
      title: typeof data.title === "string" && data.title ? data.title : "AdminOS",
      body: typeof data.body === "string" ? data.body : "",
      // Only paths inside the app; anything else opens Home.
      url: typeof data.url === "string" && data.url.startsWith("/") && !data.url.startsWith("//") ? data.url : "/",
      tag: typeof data.tag === "string" ? data.tag : undefined,
    };
  } catch {
    return { title: "AdminOS", body: "You have a new notification.", url: "/", tag: undefined };
  }
}

self.addEventListener("push", (event) => {
  const n = parse(event);
  event.waitUntil(
    self.registration.showNotification(n.title, { body: n.body, tag: n.tag, data: { url: n.url }, icon: "/icon-192.png", badge: "/icon-192.png" }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL(event.notification.data?.url ?? "/", self.location.origin).href;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      const open = windows.find((w) => new URL(w.url).origin === self.location.origin);
      if (open) {
        await open.focus();
        return open.navigate(target);
      }
      return self.clients.openWindow(target);
    })(),
  );
});
