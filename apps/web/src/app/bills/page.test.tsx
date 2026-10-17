import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import DuesPage, { shiftMonth, standing } from "./page";

const dues = (month = fx.duesMonth()) => http.get("/api/v1/dues", () => ok(month));

describe("Dues & renewals (AOS-503)", () => {
  it("shows the month's totals and each day's bills and renewals, from the user's own today", async () => {
    nav.pathname = "/bills";
    server.use(dues());
    renderWithQuery(<DuesPage />);
    expect(await screen.findByText("October 2026")).toBeInTheDocument();
    const totals = screen.getByLabelText("Totals");
    expect(within(totals).getByText("To pay").nextSibling).toHaveTextContent("₹20,800");
    expect(within(totals).getByText("Paid").nextSibling).toHaveTextContent("₹599");
    expect(within(totals).getByText("Renewals (expected)").nextSibling).toHaveTextContent("₹649");

    const card = screen.getByRole("region", { name: "Sat, 17 Oct" });
    expect(within(card).getByText("HDFC Credit Card")).toBeInTheDocument();
    expect(within(card).getByText("Credit card bill · minimum ₹922.50")).toBeInTheDocument();
    expect(within(card).getByText("Due tomorrow")).toBeInTheDocument();
    const day20 = screen.getByRole("region", { name: "Tue, 20 Oct" });
    expect(within(day20).getByText("Monthly renewal · from your past payments")).toBeInTheDocument();
    expect(within(day20).getByText("Expected")).toBeInTheDocument();
    expect(within(day20).getByText("Due in 4d")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Sat, 10 Oct" })).getByText("Paid")).toBeInTheDocument();
    expect(screen.queryByText("Back to this month")).not.toBeInTheDocument();
  });

  it("the month is in the URL: next/previous push it, this month drops it", async () => {
    nav.pathname = "/bills";
    nav.search = new URLSearchParams("m=2026-11");
    server.use(dues(fx.duesMonth({ month: "2026-11", items: [], totals: { open: "0.00", paid: "0.00", renewals: "0.00" } })));
    const { user } = renderWithQuery(<DuesPage />);
    expect(await screen.findByText("Nothing due in November 2026.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next month" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/bills?m=2026-12", { scroll: false });
    await user.click(screen.getByRole("button", { name: "Previous month" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/bills", { scroll: false });
    await user.click(screen.getByRole("button", { name: "Back to this month" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/bills", { scroll: false });
  });

  it("asks the API for the month in the URL, and ignores a malformed one", async () => {
    const asked: string[] = [];
    server.use(http.get("/api/v1/dues", ({ request }) => { asked.push(new URL(request.url).search); return ok(fx.duesMonth()); }));
    nav.search = new URLSearchParams("m=2026-13");
    const { unmount } = renderWithQuery(<DuesPage />);
    await screen.findByText("October 2026");
    unmount();
    nav.search = new URLSearchParams("m=2027-01");
    renderWithQuery(<DuesPage />);
    await screen.findAllByText(/2026/);
    expect(asked).toEqual(["", "?month=2027-01"]);
  });

  it("overdue bills from earlier months are listed first", async () => {
    server.use(dues(fx.duesMonth({ overdue: [fx.due({ id: "o-1", name: "BESCOM", date: "2026-09-28", status: "overdue" })] })));
    renderWithQuery(<DuesPage />);
    const late = await screen.findByRole("region", { name: "Overdue from earlier months" });
    expect(within(late).getByText("BESCOM")).toBeInTheDocument();
    expect(within(late).getByText("18d overdue")).toBeInTheDocument();
  });

  it("a load error can be retried", async () => {
    let broken = true;
    server.use(http.get("/api/v1/dues", () => (broken ? fail(500, "SERVER_001", "Internal server error") : ok(fx.duesMonth()))));
    const { user } = renderWithQuery(<DuesPage />);
    const retry = await screen.findByRole("button", { name: "Try again" });
    broken = false;
    await user.click(retry);
    expect(await screen.findByText("October 2026")).toBeInTheDocument();
  });

  it("standing words cover every case", () => {
    const today = "2026-10-16";
    expect(standing(fx.due({ date: "2026-10-16" }), today)).toEqual({ text: "Due today", tone: "soon" });
    expect(standing(fx.due({ date: "2026-10-19" }), today)).toEqual({ text: "Due in 3d", tone: "soon" });
    expect(standing(fx.due({ date: "2026-10-30" }), today)).toEqual({ text: "Due in 14d", tone: "calm" });
    expect(standing(fx.due({ date: "2026-10-14" }), today)).toEqual({ text: "2d overdue", tone: "late" });
    expect(standing(fx.due({ date: "2026-10-20", status: "overdue" }), today)).toEqual({ text: "Overdue", tone: "late" });
    expect(standing(fx.due({ status: "paid" }), today)).toEqual({ text: "Paid", tone: "done" });
    expect(standing(fx.due({ kind: "renewal", trial: true }), today)).toEqual({ text: "Trial ends", tone: "calm" });
  });

  it("mandate debits read as AutoPay", async () => {
    server.use(dues(fx.duesMonth({ items: [fx.due({ kind: "mandate", id: "m-1", name: "NETFLIX", amount: "649.00", status: "expected", detail: "upi_autopay" })] })));
    renderWithQuery(<DuesPage />);
    expect(await screen.findByText("UPI AutoPay · taken automatically")).toBeInTheDocument();
    expect(standing(fx.due({ kind: "mandate" }), "2026-10-16")).toEqual({ text: "AutoPay", tone: "calm" });
  });

  it("months roll over the year both ways", () => {
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
  });
});
