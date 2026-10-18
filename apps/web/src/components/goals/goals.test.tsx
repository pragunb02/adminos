import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { cx } from "../../../test/coachFixtures";
import { renderWithQuery } from "../../../test/render";
import { currentText, goalPath, goalTitle, streakText, validGoalValue } from "@/lib/goals";
import GoalsPanel from "./GoalsPanel";
import GoalsHomeCard from "./GoalsHomeCard";

describe("goal helpers (AOS-907)", () => {
  it("describes goals in words", () => {
    expect(goalTitle(cx.goal())).toBe("Cut Quick commerce by 30% (to ₹479 a week)");
    expect(goalTitle(cx.goal({ kind: "cap_amount", label: "Travel", target: "5000.00", period: "monthly" }))).toBe("Keep Travel under ₹5,000 a month");
    expect(streakText(cx.goal())).toBe("3-day no-spend streak (best 7)");
    expect(streakText(cx.goal({ streak: 7, bestStreak: 7 }))).toBe("7-day no-spend streak");
    expect(currentText(cx.goal())).toBe("This week: ₹300 of ₹479");
    expect(currentText(cx.goal({ period: "monthly", periods: [{ ...cx.goal().periods[1], current: true }] }))).toBe("This month: ₹1,100 of ₹479 (over)");
    expect(currentText(cx.goal({ periods: [] }))).toBe("");
    expect(goalPath("a/b")).toBe("/api/v1/coach/goals/a%2Fb");
  });

  it("validates values like the API", () => {
    expect(validGoalValue("cut_percent", "30")).toBe(true);
    for (const bad of ["0", "91", "30.5", "", "abc"]) expect(validGoalValue("cut_percent", bad), bad).toBe(false);
    expect(validGoalValue("cap_amount", "4000.50")).toBe(true);
    for (const bad of ["0", "-1", "1e5", "<b>"]) expect(validGoalValue("cap_amount", bad), bad).toBe(false);
  });
});

function record(over: { list?: () => Response; post?: () => Response } = {}) {
  const sent: { method: string; path: string; body: unknown }[] = [];
  server.use(
    http.get("/api/v1/coach/goals", () => over.list?.() ?? ok([cx.goal()])),
    http.post("/api/v1/coach/goals", async ({ request }) => { sent.push({ method: "POST", path: "/api/v1/coach/goals", body: await request.json() }); return over.post?.() ?? ok(cx.goal()); }),
    http.delete("/api/v1/coach/goals/:id", ({ request }) => { sent.push({ method: "DELETE", path: new URL(request.url).pathname, body: null }); return ok({ deleted: true }); }),
  );
  return sent;
}

describe("Goals on the leaks page (AOS-907)", () => {
  it("shows progress, past weeks in words and the streak", async () => {
    record();
    renderWithQuery(<GoalsPanel />);
    const g = await screen.findByRole("group", { name: "Cut Quick commerce by 30% (to ₹479 a week)" });
    expect(within(g).getByText("This week: ₹300 of ₹479 · 3-day no-spend streak (best 7)")).toBeInTheDocument();
    const past = within(g).getByRole("list", { name: "Quick commerce past weeks" });
    expect(within(past).getByText("28 Sept: ✓ met (₹400)")).toBeInTheDocument();
    expect(within(past).getByText("5 Oct: ✗ over (₹1,100)")).toBeInTheDocument();
    expect(within(g).getByText(/From your average of ₹684.78 a week over the 3 months before 28 Sept/)).toBeInTheDocument();
  });

  it("sets a cut or cap goal and deletes one", async () => {
    const sent = record();
    const { user } = renderWithQuery(<GoalsPanel />);
    const set = await screen.findByRole("button", { name: "Set goal" });
    expect(set).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("Goal habit"), await screen.findByRole("option", { name: "Nights out" }));
    await user.type(screen.getByLabelText("Cut by percent"), "95");
    expect(set).toBeDisabled();
    await user.clear(screen.getByLabelText("Cut by percent"));
    await user.type(screen.getByLabelText("Cut by percent"), "25");
    await user.click(set);
    await user.selectOptions(screen.getByLabelText("Goal type"), "cap_amount");
    await user.selectOptions(screen.getByLabelText("Goal period"), "monthly");
    await user.type(screen.getByLabelText("Keep under rupees"), "4000");
    await user.click(set);
    await user.click(screen.getByRole("button", { name: "Delete goal: Cut Quick commerce by 30% (to ₹479 a week)" }));
    await waitFor(() => expect(sent).toEqual([
      { method: "POST", path: "/api/v1/coach/goals", body: { lens: "nights_out", kind: "cut_percent", value: "25", period: "weekly" } },
      { method: "POST", path: "/api/v1/coach/goals", body: { lens: "nights_out", kind: "cap_amount", value: "4000", period: "monthly" } },
      { method: "DELETE", path: "/api/v1/coach/goals/goal-1", body: null },
    ]));
  });

  it("empty, failed save and failed load with retry", async () => {
    record({ list: () => ok([]), post: () => fail(409, "COACH_006", "Nothing spent on this habit, set a cap instead") });
    const { user, unmount } = renderWithQuery(<GoalsPanel />);
    expect(await screen.findByText(/No goals yet/)).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Goal habit"), await screen.findByRole("option", { name: "Travel" }));
    await user.type(screen.getByLabelText("Cut by percent"), "30");
    await user.click(screen.getByRole("button", { name: "Set goal" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("set a cap instead");
    unmount();
    let n = 0;
    server.use(http.get("/api/v1/coach/goals", () => (n++ === 0 ? fail(500, "SERVER_001", "Down") : ok([cx.goal()]))));
    const again = renderWithQuery(<GoalsPanel />);
    await again.user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/3-day no-spend streak/)).toBeInTheDocument();
  });
});

describe("Home's goals card (AOS-907)", () => {
  it("shows each goal's week and streak, linking to the leaks page", async () => {
    record();
    renderWithQuery(<GoalsHomeCard />);
    expect(await screen.findByText("This week: ₹300 of ₹479 · 3-day no-spend streak (best 7)")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All ›" })).toHaveAttribute("href", "/leaks");
  });

  it("renders nothing without goals, and an error with retry", async () => {
    const { container } = renderWithQuery(<GoalsHomeCard />);
    await waitFor(() => expect(container).toBeEmptyDOMElement());
    let n = 0;
    server.use(http.get("/api/v1/coach/goals", () => (n++ === 0 ? fail(500, "SERVER_001", "Down") : ok([cx.goal()]))));
    const again = renderWithQuery(<GoalsHomeCard />);
    await again.user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/no-spend streak/)).toBeInTheDocument();
  });
});
