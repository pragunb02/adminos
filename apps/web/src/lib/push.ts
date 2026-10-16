import { ApiError, deleteData, postData } from "@/lib/query";

/**
 * Web Push in the browser (AOS-510). The service worker is `public/sw.js`; the API keeps one
 * subscription per device. iPhone Safari offers push only to an app added to the Home Screen.
 */

export type PushSupport = "supported" | "needs-install" | "unsupported";

export interface PushStatus {
  enabled: boolean;
  publicKey: string | null;
  devices: { id: string; label: string | null; createdAt: string; lastSuccessAt: string | null }[];
}

export interface PushTestResult {
  devices: number;
  delivered: boolean;
  detail: string;
}

/** Why turning push on didn't work, in words the Settings page can show. */
export class PushSetupError extends Error {}

const isIos = (ua: string) => /iPhone|iPad|iPod/.test(ua);
const isStandalone = () =>
  window.matchMedia?.("(display-mode: standalone)").matches === true ||
  (navigator as Navigator & { standalone?: boolean }).standalone === true;

/** Can this browser receive push, or does it first need "Add to Home Screen" (iPhone)? */
export function pushSupport(): PushSupport {
  const capable = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  if (capable) return "supported";
  return isIos(navigator.userAgent) && !isStandalone() ? "needs-install" : "unsupported";
}

/** Notification permission, or "default" where the API doesn't exist. */
export const permission = (): NotificationPermission => ("Notification" in window ? Notification.permission : "default");

/** e.g. "iPhone · Safari", "Mac · Chrome" — so Settings can tell devices apart. */
export function deviceLabel(ua: string = navigator.userAgent): string {
  const device = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : "Computer";
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) || /CriOS\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  return `${device} · ${browser}`;
}

/** The VAPID public key (URL-safe base64) as the bytes `pushManager.subscribe` wants. */
export function keyBytes(base64Url: string): Uint8Array<ArrayBuffer> {
  const base64 = (base64Url + "=".repeat((4 - (base64Url.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

const registration = () => navigator.serviceWorker.register("/sw.js", { scope: "/" });

/** This device's current subscription, if push is on here. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  return (await registration()).pushManager.getSubscription();
}

/**
 * Asks permission, subscribes this device and registers it with the API. If the API refuses, the
 * browser subscription is undone, so the two never disagree.
 */
export async function turnOn(publicKey: string): Promise<void> {
  const granted = await Notification.requestPermission();
  if (granted !== "granted") {
    throw new PushSetupError(granted === "denied" ? "Notifications are blocked for AdminOS. Allow them in your browser or iPhone settings, then try again." : "Notifications weren't allowed.");
  }
  const reg = await registration();
  const subscription = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
  const json = subscription.toJSON();
  try {
    await postData("/api/v1/push/subscriptions", { endpoint: json.endpoint, keys: json.keys, label: deviceLabel() });
  } catch (e) {
    await subscription.unsubscribe().catch(() => undefined);
    throw e;
  }
}

/** Turns push off for this device, on the server and in the browser. Already off on the server is fine. */
export async function turnOff(): Promise<void> {
  const subscription = await currentSubscription();
  if (!subscription) return;
  try {
    await deleteData("/api/v1/push/subscriptions", { endpoint: subscription.endpoint });
  } catch (e) {
    if (!(e instanceof ApiError && e.code === "PUSH_002")) throw e;
  }
  await subscription.unsubscribe();
}

export const sendTest = () => postData<PushTestResult>("/api/v1/push/test", {});
