import { http } from "msw";
import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { renderWithQuery } from "../../test/render";
import { IPHONE_SAFARI, installPushBrowser, removePushBrowser } from "../../test/push";
import PushDevice from "./PushDevice";

const on = (devices = 0) => http.get("/api/v1/push", () => ok({
  enabled: true, publicKey: "AQID",
  devices: Array.from({ length: devices }, (_, i) => ({ id: `d${i}`, label: "iPhone · Safari", createdAt: "2026-10-16T09:00:00Z", lastSuccessAt: null })),
}));

afterEach(() => removePushBrowser());

describe("Settings → Notifications on this device (AOS-510)", () => {
  it("says when the server has no push keys", async () => {
    installPushBrowser();
    renderWithQuery(<PushDevice />);
    expect(await screen.findByText(/aren't set up on the server yet/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("on iPhone in a browser tab, explains Add to Home Screen", async () => {
    Object.defineProperty(navigator, "userAgent", { value: IPHONE_SAFARI, configurable: true });
    server.use(on());
    renderWithQuery(<PushDevice />);
    expect(await screen.findByText(/Add to Home Screen/)).toBeInTheDocument();
  });

  it("says when this browser can't do push", async () => {
    server.use(on());
    renderWithQuery(<PushDevice />);
    expect(await screen.findByText("This browser can't receive push notifications.")).toBeInTheDocument();
  });

  it("says when notifications are blocked", async () => {
    installPushBrowser({ permission: "denied" });
    server.use(on());
    renderWithQuery(<PushDevice />);
    expect(await screen.findByText(/blocked for AdminOS/)).toBeInTheDocument();
  });

  it("turn on → on for this device, with a confirmation", async () => {
    installPushBrowser();
    server.use(on(), http.post("/api/v1/push/subscriptions", () => ok({ id: "d1" })));
    const { user } = renderWithQuery(<PushDevice />);
    expect(await screen.findByText("Off for this device.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Turn on notifications" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Notifications are on for this device.");
    expect(screen.getByRole("button", { name: "Send a test" })).toBeInTheDocument();
  });

  it("when on: send a test (sent or not), and turn off", async () => {
    installPushBrowser({ subscribed: true });
    let delivered = true;
    server.use(
      on(2),
      http.post("/api/v1/push/test", () => ok({ devices: 2, delivered, detail: delivered ? "2 of 2 devices" : "The push service failed (503)" })),
      http.delete("/api/v1/push/subscriptions", () => ok({ removed: true })),
    );
    const { user } = renderWithQuery(<PushDevice />);
    expect(await screen.findByText(/On for this device\. Push is on for 2 devices in total\./)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Send a test" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Test sent");
    delivered = false;
    await user.click(screen.getByRole("button", { name: "Send a test" }));
    expect(await screen.findByRole("status")).toHaveTextContent("The test didn't go through: The push service failed (503)");
    await user.click(screen.getByRole("button", { name: "Turn off" }));
    expect(await screen.findByText(/Off for this device/)).toBeInTheDocument();
  });

  it("one device is singular", async () => {
    installPushBrowser({ subscribed: true });
    server.use(on(1));
    renderWithQuery(<PushDevice />);
    expect(await screen.findByText(/Push is on for 1 device in total\./)).toBeInTheDocument();
  });

  it("a failure is shown as an alert", async () => {
    installPushBrowser();
    server.use(on(), http.post("/api/v1/push/subscriptions", () => fail(503, "PUSH_001", "Push notifications aren't set up on this server")));
    const { user } = renderWithQuery(<PushDevice />);
    await user.click(await screen.findByRole("button", { name: "Turn on notifications" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Push notifications aren't set up on this server");
  });

  it("if the browser can't tell whether it's subscribed, it shows off", async () => {
    const b = installPushBrowser();
    b.pushManager.getSubscription.mockRejectedValueOnce(new Error("no service worker"));
    server.use(on());
    renderWithQuery(<PushDevice />);
    expect(await screen.findByText("Off for this device.")).toBeInTheDocument();
  });

  it("a status load error is shown", async () => {
    server.use(http.get("/api/v1/push", () => fail(400, "VALIDATION_001", "Bad request")));
    renderWithQuery(<PushDevice />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Bad request");
  });
});
