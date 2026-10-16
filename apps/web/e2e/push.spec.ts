import { expect, expectAccessible, test } from "./support/fixtures";
import type { MockApi } from "./support/mock-api";

/**
 * Web Push in a real browser (AOS-510): the Home Screen manifest and icons, the service worker, and
 * Settings → Notifications on this device. Push services can't be reached from tests, so the
 * browser's subscribe/permission calls are stubbed; everything else (service worker registration,
 * the page, the API calls) is real.
 */
const fakePush = () => {
  const endpoint = "https://fcm.googleapis.com/fcm/send/e2e-device";
  let current: unknown = null;
  const sub = { endpoint, toJSON: () => ({ endpoint, keys: { p256dh: "BCVx-key", auth: "BTBZ-auth" } }), unsubscribe: async () => { current = null; return true; } };
  if (typeof PushManager !== "undefined") {
    PushManager.prototype.getSubscription = async () => current as PushSubscription | null;
    PushManager.prototype.subscribe = async () => { current = sub; return sub as unknown as PushSubscription; };
  }
  if (typeof Notification !== "undefined") {
    Object.defineProperty(Notification, "permission", { get: () => "default", configurable: true });
    Notification.requestPermission = async () => "granted";
  }
};

function pushOn(api: MockApi) {
  const devices: { id: string; label: string; createdAt: string; lastSuccessAt: null }[] = [];
  api.on("GET /api/v1/push", () => ({ data: { enabled: true, publicKey: "AQIDBA", devices } }))
    .on("POST /api/v1/push/subscriptions", (req) => {
      const body = req.postDataJSON() as { label: string };
      devices.push({ id: "d1", label: body.label, createdAt: "2026-10-16T09:00:00Z", lastSuccessAt: null });
      return { status: 201, data: devices[0] };
    })
    .on("POST /api/v1/push/test", () => ({ data: { devices: 1, delivered: true, detail: "1 of 1 devices" } }))
    .on("DELETE /api/v1/push/subscriptions", () => { devices.length = 0; return { data: { removed: true } }; });
}

test.describe("Web Push", () => {
  test("the app can be added to the Home Screen: manifest, icons and service worker are served", async ({ page }) => {
    const manifest = await (await page.request.get("/manifest.webmanifest")).json();
    expect(manifest).toMatchObject({ name: "AdminOS", display: "standalone", start_url: "/" });
    for (const icon of manifest.icons as { src: string }[]) expect((await page.request.get(icon.src)).status()).toBe(200);
    const sw = await page.request.get("/sw.js");
    expect(sw.status()).toBe(200);
    expect(sw.headers()["content-type"]).toContain("javascript");
    await page.goto("/login");
    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute("href", "/manifest.webmanifest");
    await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveCount(1);
  });

  test("Settings explains that push isn't set up on the server", async ({ signedIn: page }) => {
    await page.goto("/settings");
    await expect(page.getByRole("region", { name: "Notifications on this device" })).toContainText("aren't set up on the server yet");
  });

  test("turn on → send a test → turn off, with the service worker really registered", async ({ signedIn: page, api, browserName, isMobile }) => {
    test.skip(browserName === "webkit", "iPhone Safari in a tab: covered by the Add to Home Screen test");
    await page.addInitScript(fakePush);
    pushOn(api);
    await page.goto("/settings");
    const section = page.getByRole("region", { name: "Notifications on this device" });
    await expect(section).toContainText("Off for this device.");
    await section.getByRole("button", { name: "Turn on notifications" }).click();
    await expect(section.getByRole("status")).toHaveText("Notifications are on for this device.");
    await expect(section).toContainText("Push is on for 1 device in total.");
    const registered = api.callsTo("POST", "/api/v1/push/subscriptions")[0].body as { endpoint: string; label: string };
    expect(registered.endpoint).toBe("https://fcm.googleapis.com/fcm/send/e2e-device");
    expect(registered.label).toContain(isMobile ? "Android" : "·");
    expect(await page.evaluate(async () => (await navigator.serviceWorker.getRegistration("/"))?.active?.scriptURL ?? (await navigator.serviceWorker.ready).active?.scriptURL)).toMatch(/\/sw\.js$/);

    await section.getByRole("button", { name: "Send a test" }).click();
    await expect(section.getByRole("status")).toContainText("Test sent");
    await expectAccessible(page);

    await section.getByRole("button", { name: "Turn off" }).click();
    await expect(section).toContainText("Off for this device.");
    expect(api.callsTo("DELETE", "/api/v1/push/subscriptions")[0].body).toEqual({ endpoint: "https://fcm.googleapis.com/fcm/send/e2e-device" });
  });

  test("on iPhone Safari outside the Home Screen app, Settings explains Add to Home Screen", async ({ signedIn: page, api, browserName }) => {
    test.skip(browserName !== "webkit", "iPhone only");
    await page.addInitScript(() => { delete (window as unknown as Record<string, unknown>).PushManager; });
    pushOn(api);
    await page.goto("/settings");
    await expect(page.getByRole("region", { name: "Notifications on this device" })).toContainText("Add to Home Screen");
  });
});
