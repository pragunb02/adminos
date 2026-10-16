import { http } from "msw";
import { screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import BillsPage from "./page";

describe("Bills", () => {
  beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }); vi.setSystemTime(new Date("2026-10-16T06:00:00Z")); });
  afterEach(() => vi.useRealTimers());

  it("labels each due state and highlights urgent bills", async () => {
    server.use(http.get("/api/v1/bills/upcoming", () => ok([
      fx.bill({ id: "1", billerName: "Today", dueDate: "2026-10-16T06:00:00Z" }),
      fx.bill({ id: "2", billerName: "Tomorrow", dueDate: "2026-10-17T06:00:00Z" }),
      fx.bill({ id: "3", billerName: "Later", dueDate: "2026-10-26T06:00:00Z", minimumDue: null }),
      fx.bill({ id: "4", billerName: "Late", dueDate: "2026-10-13T06:00:00Z" }),
      fx.bill({ id: "5", billerName: "Past due", status: "overdue" }),
      fx.bill({ id: "6", billerName: "Settled", status: "paid", dueDate: "2026-10-17T06:00:00Z" }),
      fx.bill({ id: "7", billerName: "Same min", minimumDue: 24500, dueDate: "2026-10-30T06:00:00Z" }),
    ])));
    renderWithQuery(<BillsPage />);
    expect(screen.getByText("Loading bills...")).toBeInTheDocument();
    for (const label of ["Due today", "Due tomorrow", "Due in 10d", "3d overdue", "Overdue", "Paid", "Due in 14d"]) {
      expect(await screen.findByText(label)).toBeInTheDocument();
    }
    expect(screen.getAllByText("Min: ₹1,225").length).toBeGreaterThan(0);
    const card = (name: string) => screen.getByText(name).closest(".rounded-lg")!;
    expect(card("Tomorrow")).toHaveClass("border-red-200");
    expect(card("Later")).toHaveClass("border-gray-200");
    expect(card("Settled")).toHaveClass("border-gray-200");
    expect(card("Same min")).not.toHaveTextContent("Min:");
    expect(card("Later")).not.toHaveTextContent("Min:");
  });

  it("empty state", async () => {
    server.use(http.get("/api/v1/bills/upcoming", () => ok([])));
    renderWithQuery(<BillsPage />);
    expect(await screen.findByText("No upcoming bills")).toBeInTheDocument();
  });

  it("error, then retry", async () => {
    let n = 0;
    server.use(http.get("/api/v1/bills/upcoming", () => (++n === 1 ? fail(500, "SERVER_001", "Failed") : ok([fx.bill()]))));
    const { user } = renderWithQuery(<BillsPage />);
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("HDFC Card")).toBeInTheDocument();
  });
});
