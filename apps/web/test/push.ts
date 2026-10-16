import { vi } from "vitest";

/**
 * A stand-in for the browser's push machinery (service worker, PushManager, Notification), which
 * jsdom doesn't have (AOS-510). Real browsers are covered by Playwright.
 */
export function installPushBrowser(opts: { subscribed?: boolean; permission?: NotificationPermission; answer?: NotificationPermission; ua?: string } = {}) {
  const endpoint = "https://web.push.apple.com/QGtest-device";
  const subscription = {
    endpoint,
    toJSON: () => ({ endpoint, keys: { p256dh: "BCVx-key", auth: "BTBZ-auth" } }),
    unsubscribe: vi.fn(async () => true),
  };
  let current: typeof subscription | null = opts.subscribed ? subscription : null;
  const pushManager = {
    getSubscription: vi.fn(async () => current),
    subscribe: vi.fn(async () => (current = subscription)),
  };
  const register = vi.fn(async () => ({ pushManager }));
  const notification = { permission: opts.permission ?? "default", requestPermission: vi.fn(async () => opts.answer ?? "granted") };
  Object.defineProperty(navigator, "serviceWorker", { value: { register }, configurable: true });
  Object.defineProperty(window, "PushManager", { value: function PushManager() {}, configurable: true });
  Object.defineProperty(window, "Notification", { value: notification, configurable: true });
  if (opts.ua) Object.defineProperty(navigator, "userAgent", { value: opts.ua, configurable: true });
  return { subscription, pushManager, register, notification, unsubscribed: () => (current = null) };
}

/** Removes the stand-in, back to jsdom (no push). */
export function removePushBrowser() {
  for (const [target, key] of [[navigator, "serviceWorker"], [window, "PushManager"], [window, "Notification"], [navigator, "userAgent"]] as const) {
    if (Object.getOwnPropertyDescriptor(target, key)?.configurable) delete (target as unknown as Record<string, unknown>)[key];
  }
}

export const IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
