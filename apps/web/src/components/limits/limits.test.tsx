import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { cx } from "../../../test/coachFixtures";
import { renderWithQuery } from "../../../test/render";
import LimitsPanel from "./LimitsPanel";
import CoachSettingsSection from "./CoachSettingsSection";

/** Records what the components sent to the limits API. */
function record(over: { post?: () => Response; patch?: () => Response; del?: () => Response; limits?: () => Response } = {}) {
  const sent: { method: string; path: string; body: unknown }[] = [];
  const note = async (method: string, request: Request) => {
    const text = await request.text();
    sent.push({ method, path: new URL(request.url).pathname, body: text ? JSON.parse(text) : null });
  };
  server.use(
    http.get("/api/v1/coach/limits", () => over.limits?.() ?? ok(cx.limitsReady())),
    http.post("/api/v1/coach/limits", async ({ request }) => { await note("POST", request); return over.post?.() ?? ok(cx.limit()); }),
    http.post("/api/v1/coach/limits/suggestions/:lens/dismiss", async ({ request }) => { await note("POST", request); return ok({ dismissed: true }); }),
    http.patch("/api/v1/coach/limits/:id", async ({ request }) => { await note("PATCH", request); return over.patch?.() ?? ok(cx.limit()); }),
    http.delete("/api/v1/coach/limits/:id", async ({ request }) => { await note("DELETE", request); return over.del?.() ?? ok({ deleted: true }); }),
    http.patch("/api/v1/coach/settings", async ({ request }) => { await note("PATCH", request); return ok({ limitAlerts: false }); }),
  );
  return sent;
}

describe("Limits on the leaks page (AOS-904)", () => {
  it("during the learning month it only says how far along", async () => {
    server.use(http.get("/api/v1/coach/limits", () => ok(cx.limits())));
    renderWithQuery(<LimitsPanel />);
    expect(await screen.findByRole("status")).toHaveTextContent("Learning your habits: 12 of 30 days");
    expect(screen.queryByRole("button", { name: "Accept" })).not.toBeInTheDocument();
  });

  it("shows each limit's progress in words and a bar", async () => {
    record();
    renderWithQuery(<LimitsPanel />);
    const bar = await screen.findByRole("progressbar", { name: "Quick commerce weekly limit" });
    expect(bar).toHaveAttribute("aria-valuenow", "60");
    expect(screen.getByText("60% used · ₹800 left · last day")).toBeInTheDocument();
    expect(screen.getByText("₹1,200 of ₹2,000")).toBeInTheDocument();
  });

  it("a suggestion shows its working and is accepted as is", async () => {
    const sent = record();
    const { user } = renderWithQuery(<LimitsPanel />);
    const s = await screen.findByRole("group", { name: "Suggested limit for Food delivery" });
    expect(within(s).getByText(/A suggested limit is ready/)).toHaveTextContent("Food delivery, ₹1,100 a week");
    await user.click(within(s).getByText("How we got this"));
    expect(within(s).getByText("Your last 4 full weeks: ₹1,500, ₹1,500, ₹1,000, ₹1,000.")).toBeInTheDocument();
    expect(within(s).getByText("Median ₹1,250, minus 10%, rounded down to the nearest ₹50.")).toBeInTheDocument();
    await user.click(within(s).getByRole("button", { name: "Accept" }));
    await waitFor(() => expect(sent).toEqual([{ method: "POST", path: "/api/v1/coach/limits", body: { scope: { kind: "lens", value: "food_delivery" }, period: "weekly", amount: "1100.00" } }]));
  });

  it("a suggestion can be adjusted or put off", async () => {
    const sent = record();
    const { user } = renderWithQuery(<LimitsPanel />);
    const s = await screen.findByRole("group", { name: "Suggested limit for Food delivery" });
    await user.click(within(s).getByRole("button", { name: "Adjust" }));
    const input = within(s).getByLabelText("Weekly limit for Food delivery");
    await user.clear(input);
    await user.type(input, "abc");
    expect(within(s).getByRole("button", { name: "Set limit" })).toBeDisabled();
    await user.clear(input);
    await user.type(input, "900");
    await user.click(within(s).getByRole("button", { name: "Set limit" }));
    await user.click(within(s).getByRole("button", { name: "Cancel" }));
    await user.click(within(s).getByRole("button", { name: "Not now" }));
    await waitFor(() => expect(sent.map((x) => [x.method, x.path, x.body])).toEqual([
      ["POST", "/api/v1/coach/limits", { scope: { kind: "lens", value: "food_delivery" }, period: "weekly", amount: "900" }],
      ["POST", "/api/v1/coach/limits/suggestions/food_delivery/dismiss", {}],
    ]));
  });

  it("errors show, loading fails with a retry, and no limits says so", async () => {
    record({ post: () => fail(409, "COACH_003", "There is already a weekly limit for Food delivery") });
    const { user, unmount } = renderWithQuery(<LimitsPanel />);
    await user.click(await screen.findByRole("button", { name: "Accept" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("already a weekly limit");
    unmount();

    let n = 0;
    server.use(http.get("/api/v1/coach/limits", () => (n++ === 0 ? fail(500, "SERVER_001", "Down") : ok(cx.limits({ learning: { ...cx.limitsReady().learning } })))));
    const second = renderWithQuery(<LimitsPanel />);
    await second.user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/No limits yet/)).toBeInTheDocument();
  });
});

describe("Settings → Spending coach (AOS-904)", () => {
  it("switches limit alerts", async () => {
    const sent = record();
    const { user } = renderWithQuery(<CoachSettingsSection />);
    await user.click(await screen.findByRole("checkbox", { name: "Limit alerts at 50%, 80% and 100%" }));
    await waitFor(() => expect(sent).toEqual([{ method: "PATCH", path: "/api/v1/coach/settings", body: { limitAlerts: false } }]));
  });

  it("the evening digest: off, a new time on leaving the field, and skip zero days (AOS-905)", async () => {
    const sent = record();
    const { user } = renderWithQuery(<CoachSettingsSection />);
    const time = await screen.findByLabelText("Digest time");
    expect(time).toHaveValue("21:30");
    await user.clear(time);
    await user.type(time, "20:15");
    await user.tab();
    await user.click(screen.getByRole("checkbox", { name: "Skip on a day with no spending" }));
    await user.click(screen.getByRole("checkbox", { name: "Evening digest" }));
    await waitFor(() => expect(sent.map((x) => x.body)).toEqual([{ digestTime: "20:15" }, { digestSkipZeroDay: true }, { eveningDigest: false }]));
    expect(screen.getByText("During quiet hours the digest waits until they end.")).toBeInTheDocument();
  });

  it("changes and deletes a limit", async () => {
    const sent = record();
    const { user } = renderWithQuery(<CoachSettingsSection />);
    await user.click(await screen.findByRole("button", { name: "Change Quick commerce weekly limit" }));
    const input = screen.getByLabelText("New amount for Quick commerce");
    await user.clear(input);
    await user.type(input, "2500");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await user.click(screen.getByRole("button", { name: "Change Quick commerce weekly limit" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(screen.getByRole("button", { name: "Delete Quick commerce weekly limit" }));
    await waitFor(() => expect(sent.map((x) => [x.method, x.path, x.body])).toEqual([
      ["PATCH", "/api/v1/coach/limits/lim-1", { amount: "2500" }],
      ["DELETE", "/api/v1/coach/limits/lim-1", null],
    ]));
  });

  it("adds a habit, merchant or category limit", async () => {
    const sent = record();
    const { user } = renderWithQuery(<CoachSettingsSection />);
    const add = await screen.findByRole("button", { name: "Add limit" });
    expect(add).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("Habit"), await screen.findByRole("option", { name: "Nights out" }));
    await user.type(screen.getByLabelText("Amount in rupees"), "3000");
    await user.click(add);
    await user.selectOptions(screen.getByLabelText("Limit on"), "merchant");
    await user.type(screen.getByLabelText("Merchant"), " Zepto ");
    await user.selectOptions(screen.getByLabelText("Period"), "monthly");
    await user.type(screen.getByLabelText("Amount in rupees"), "4000");
    await user.click(add);
    await user.selectOptions(screen.getByLabelText("Limit on"), "category");
    expect(screen.queryByRole("option", { name: "Transfer" })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Category"), "food");
    await user.type(screen.getByLabelText("Amount in rupees"), "8000");
    await user.click(add);
    await waitFor(() => expect(sent.map((x) => x.body)).toEqual([
      { scope: { kind: "lens", value: "nights_out" }, period: "weekly", amount: "3000" },
      { scope: { kind: "merchant", value: "Zepto" }, period: "monthly", amount: "4000" },
      { scope: { kind: "category", value: "food" }, period: "monthly", amount: "8000" },
    ]));
  });

  it("during the learning month there is no add form, and errors show", async () => {
    record({ limits: () => ok(cx.limits()) });
    renderWithQuery(<CoachSettingsSection />);
    expect(await screen.findByRole("status")).toHaveTextContent("Learning your habits: 12 of 30 days. Limits open after 30 days.");
    expect(screen.getByText("No limits.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add limit" })).not.toBeInTheDocument();
  });

  it("a failed save is shown", async () => {
    record({ del: () => fail(404, "COACH_002", "Limit not found") });
    const { user } = renderWithQuery(<CoachSettingsSection />);
    await user.click(await screen.findByRole("button", { name: "Delete Quick commerce weekly limit" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Limit not found");
  });
});
