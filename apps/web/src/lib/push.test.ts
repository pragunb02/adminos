import { http } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { IPHONE_SAFARI, installPushBrowser, removePushBrowser } from "../../test/push";
import { deviceLabel, keyBytes, PushSetupError, pushSupport, sendTest, turnOff, turnOn } from "./push";

afterEach(() => removePushBrowser());

describe("push support", () => {
  it("a browser with a service worker, PushManager and Notification can do push", () => {
    installPushBrowser();
    expect(pushSupport()).toBe("supported");
  });

  it("iPhone Safari in a tab needs Add to Home Screen first; other browsers without push can't", () => {
    Object.defineProperty(navigator, "userAgent", { value: IPHONE_SAFARI, configurable: true });
    expect(pushSupport()).toBe("needs-install");
    Object.defineProperty(navigator, "standalone", { value: true, configurable: true });
    expect(pushSupport()).toBe("unsupported"); // installed but still no push (old iOS)
    delete (navigator as unknown as Record<string, unknown>).standalone;
    removePushBrowser();
    expect(pushSupport()).toBe("unsupported"); // jsdom's own UA
  });

  it("labels devices so Settings can tell them apart", () => {
    expect(deviceLabel(IPHONE_SAFARI)).toBe("iPhone · Safari");
    expect(deviceLabel("Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36")).toBe("Mac · Chrome");
    expect(deviceLabel("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/141.0 Safari/537.36 Edg/141.0")).toBe("Windows · Edge");
    expect(deviceLabel("Mozilla/5.0 (Linux; Android 15) Gecko/141.0 Firefox/141.0")).toBe("Android · Firefox");
    expect(deviceLabel("Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) CriOS/141.0")).toBe("iPad · Chrome");
    expect(deviceLabel("curl/8")).toBe("Computer · Browser");
  });

  it("turns the URL-safe base64 server key into bytes", () => {
    expect(Array.from(keyBytes("AQID_-8"))).toEqual([1, 2, 3, 255, 239]);
  });
});

describe("turning push on and off", () => {
  it("asks permission, subscribes with the server key and registers the device", async () => {
    const b = installPushBrowser({ ua: IPHONE_SAFARI });
    let posted: unknown;
    server.use(http.post("/api/v1/push/subscriptions", async ({ request }) => { posted = await request.json(); return ok({ id: "d1" }); }));
    await turnOn("AQID");
    expect(b.notification.requestPermission).toHaveBeenCalled();
    expect(b.register).toHaveBeenCalledWith("/sw.js", { scope: "/" });
    expect(b.pushManager.subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: new Uint8Array([1, 2, 3]) });
    expect(posted).toEqual({ endpoint: "https://web.push.apple.com/QGtest-device", keys: { p256dh: "BCVx-key", auth: "BTBZ-auth" }, label: "iPhone · Safari" });
  });

  it("blocked or dismissed permission is explained, and nothing is subscribed", async () => {
    const b = installPushBrowser({ answer: "denied" });
    await expect(turnOn("AQID")).rejects.toThrow(/blocked for AdminOS/);
    removePushBrowser();
    const c = installPushBrowser({ answer: "default" });
    await expect(turnOn("AQID")).rejects.toBeInstanceOf(PushSetupError);
    expect(b.pushManager.subscribe).not.toHaveBeenCalled();
    expect(c.pushManager.subscribe).not.toHaveBeenCalled();
  });

  it("if the server refuses, the browser subscription is undone", async () => {
    const b = installPushBrowser();
    server.use(http.post("/api/v1/push/subscriptions", () => fail(503, "PUSH_001", "Push notifications aren't set up on this server")));
    await expect(turnOn("AQID")).rejects.toThrow("Push notifications aren't set up on this server");
    expect(b.subscription.unsubscribe).toHaveBeenCalled();
  });

  it("turning off removes the device on the server and in the browser; already off on the server is fine", async () => {
    const b = installPushBrowser({ subscribed: true });
    let body: unknown;
    server.use(http.delete("/api/v1/push/subscriptions", async ({ request }) => { body = await request.json(); return ok({ removed: true }); }));
    await turnOff();
    expect(body).toEqual({ endpoint: "https://web.push.apple.com/QGtest-device" });
    expect(b.subscription.unsubscribe).toHaveBeenCalledTimes(1);

    server.use(http.delete("/api/v1/push/subscriptions", () => fail(404, "PUSH_002", "Push isn't turned on for this device")));
    await turnOff();
    expect(b.subscription.unsubscribe).toHaveBeenCalledTimes(2);
  });

  it("a real failure while turning off keeps the browser subscription", async () => {
    const b = installPushBrowser({ subscribed: true });
    server.use(http.delete("/api/v1/push/subscriptions", () => fail(500, "SERVER_001", "Internal server error")));
    await expect(turnOff()).rejects.toThrow("Internal server error");
    expect(b.subscription.unsubscribe).not.toHaveBeenCalled();
  });

  it("turning off with nothing subscribed does nothing", async () => {
    installPushBrowser();
    await turnOff(); // no DELETE handler: an unexpected request would fail the test
  });

  it("send a test asks the server", async () => {
    server.use(http.post("/api/v1/push/test", () => ok({ devices: 1, delivered: true, detail: "1 of 1 devices" })));
    await expect(sendTest()).resolves.toEqual({ devices: 1, delivered: true, detail: "1 of 1 devices" });
  });
});
