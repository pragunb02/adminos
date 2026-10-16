import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { fx } from "../../test/fixtures";
import { nav } from "../../test/next";
import { renderWithQuery } from "../../test/render";
import Home from "./page";

/** Console Home (AOS-303). "Today" is Fri 16 Oct 2026, 12:00 in India. */
describe("console Home", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-16T06:30:00Z"));
    vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockReturnValue({ timeZone: "Asia/Kolkata" } as Intl.ResolvedDateTimeFormatOptions);
  });
  afterEach(() => vi.useRealTimers());

  /** Records the analytics ranges asked for, and answers every Home call. */
  function routes(over: Partial<Record<string, () => Response>> = {}) {
    const asked: Record<string, string[]> = {};
    const note = (what: string, url: string) => (asked[what] ??= []).push(new URL(url).search);
    server.use(
      http.get("/api/v1/analytics/summary", ({ request }) => {
        note("summary", request.url);
        const from = new URL(request.url).searchParams.get("from")!;
        return over.summary?.() ?? ok(from.startsWith("2026-09") ? fx.summary("44050.00", "0.00") : fx.summary());
      }),
      http.get("/api/v1/analytics/series", ({ request }) => {
        note("series", request.url);
        const from = new URL(request.url).searchParams.get("from")!;
        return over.series?.() ?? ok(from === "2026-10-01" ? fx.monthSoFar() : from === "2026-09-01" ? fx.lastMonth() : fx.week());
      }),
      http.get("/api/v1/analytics/categories", ({ request }) => { note("categories", request.url); return over.categories?.() ?? ok(fx.categories()); }),
      http.get("/api/v1/analytics/merchants", ({ request }) => { note("merchants", request.url); return over.merchants?.() ?? ok(fx.merchants()); }),
      http.get("/api/v1/transactions", ({ request }) => { note("latest", request.url); return over.latest?.() ?? ok([fx.transaction(), fx.transaction({ id: "t-2", type: "credit", nature: "refund", amount: "300.00", merchantName: "Amazon" })]); }),
      http.get("/api/v1/bills/upcoming", () => over.bills?.() ?? ok([fx.bill({ dueDate: "2026-10-18" }), fx.bill({ id: "b-far", billerName: "Far away", dueDate: "2026-11-30" })])),
      http.get("/api/v1/transactions/suggestions", () => over.reviews?.() ?? ok([{}, {}, {}])),
      http.get("/api/v1/dashboard/home", () => over.home?.() ?? ok(fx.home())),
    );
    return asked;
  }

  it("headline: spent so far vs the same days of last month, in words with an arrow; received and net", async () => {
    const asked = routes();
    renderWithQuery(<Home />);
    expect(await screen.findByText("₹48,250")).toBeInTheDocument();
    expect(screen.getByText("Spent so far")).toBeInTheDocument();
    expect(screen.getByText(/₹4,200 more than by 16 Sept/)).toBeInTheDocument();
    expect(screen.getByText("Received ₹1,00,000 · Net +₹51,750")).toBeInTheDocument();
    expect(asked.summary).toEqual(expect.arrayContaining(["?from=2026-10-01&to=2026-10-16", "?from=2026-09-01&to=2026-09-16"]));
  });

  it("a month asks for every day of it and all of last month; categories, merchants and latest for the period", async () => {
    const asked = routes();
    renderWithQuery(<Home />);
    await screen.findByText("₹48,250");
    await screen.findByRole("img", { name: /Spent ₹48,250 by 16 Oct/ });
    expect(asked.series?.sort()).toEqual(["?from=2026-09-01&to=2026-09-30&bucket=day", "?from=2026-10-01&to=2026-10-16&bucket=day"]);
    expect(asked.categories).toEqual(["?from=2026-10-01&to=2026-10-16"]);
    expect(asked.merchants).toEqual(["?from=2026-10-01&to=2026-10-16&limit=5"]);
    expect(asked.latest).toEqual(["?from=2026-10-01&to=2026-10-16&limit=5"]);
  });

  it("the review banner, top 5 categories with share and change, merchants, latest, bills due within 7 days, anomalies", async () => {
    routes();
    renderWithQuery(<Home />);
    expect(await screen.findByRole("link", { name: /3 to review — they change your totals/ })).toHaveAttribute("href", "/review");
    const where = await screen.findByRole("region", { name: "Where it went" });
    expect(within(where).getAllByRole("listitem")).toHaveLength(5);
    expect(within(where).getByText("Shopping")).toBeInTheDocument();
    expect(within(where).getByText(/₹2,100 more than before/)).toBeInTheDocument();
    expect(within(where).queryByText("Education")).not.toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Top merchants" })).getByText(/18 payments/)).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Top merchants" })).getByText(/1 payment$/)).toBeInTheDocument();
    const latest = screen.getByRole("region", { name: "Latest" });
    expect(within(latest).getByText("−₹349")).toBeInTheDocument();
    expect(within(latest).getByText("+₹300")).toBeInTheDocument();
    expect(within(latest).getByText("Refund")).toBeInTheDocument();
    const coming = await screen.findByRole("region", { name: "Coming up" });
    expect(within(coming).getByText(/₹24,500 · in 2 days/)).toBeInTheDocument();
    expect(within(coming).queryByText("Far away")).not.toBeInTheDocument();
    expect(within(await screen.findByRole("region", { name: "Needs attention" })).getByRole("link")).toHaveAttribute("href", "/anomalies/an-1");
  });

  it("the week chart is an image with a sentence, and a table on request", async () => {
    nav.search = new URLSearchParams("p=week");
    routes();
    const { user } = renderWithQuery(<Home />);
    const chart = await screen.findByRole("img", { name: /Spending this week: most on Thu \(₹2,999\), today ₹350\./ });
    expect(chart).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "View as table" }));
    const table = screen.getByRole("table", { name: "Spending this week" });
    expect(within(table).getAllByRole("row")).toHaveLength(8);
    expect(within(table).getByText("₹350.50")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "View as chart" }));
    expect(screen.getByRole("img", { name: /Spending this week/ })).toBeInTheDocument();
  });

  it("a past month says Spent (not so far) and compares whole months; the period comes from the URL", async () => {
    nav.search = new URLSearchParams("p=month&d=2026-09-01");
    const asked = routes();
    renderWithQuery(<Home />);
    expect(await screen.findByRole("heading", { name: "September 2026" })).toBeInTheDocument();
    expect(await screen.findByText("Spent")).toBeInTheDocument();
    expect(asked.summary).toEqual(expect.arrayContaining(["?from=2026-09-01&to=2026-09-30", "?from=2026-08-01&to=2026-08-30"]));
    expect(await screen.findByRole("img", { name: /Spent ₹44,050 this month;/ })).toBeInTheDocument(); // September's own summary
    expect(screen.queryByRole("region", { name: "This week" })).not.toBeInTheDocument();
  });

  it("running total (AOS-304): this month vs last month, today marked, one sentence, and a table", async () => {
    routes();
    const { user } = renderWithQuery(<Home />);
    const running = await screen.findByRole("region", { name: "Running total" });
    expect(await within(running).findByRole("img", { name: "Spent ₹48,250 by 16 Oct; ₹4,200 more than by 16 Sept." })).toBeInTheDocument();
    expect(within(running).getByText(/October 2026.*September 2026/)).toBeInTheDocument(); // the legend, in words
    await user.click(within(running).getByRole("button", { name: "View as table" }));
    const table = within(running).getByRole("table", { name: "Running total" });
    expect(within(table).getAllByRole("row")).toHaveLength(31); // header + 30 days of September
    expect(within(table).getAllByText("—").length).toBeGreaterThan(10); // October hasn't reached those days
    expect(within(table).getByText("₹300.30")).toBeInTheDocument(); // exact running sum on day 2
    await user.click(within(running).getByRole("button", { name: "View as chart" }));
    expect(within(running).getByRole("img")).toBeInTheDocument();
  });

  it("day by day shows every day of the month", async () => {
    routes();
    const { user } = renderWithQuery(<Home />);
    const days = await screen.findByRole("region", { name: "Day by day" });
    await user.click(await within(days).findByRole("button", { name: "View as table" }));
    expect(within(within(days).getByRole("table")).getAllByRole("row")).toHaveLength(17); // header + 16 days
  });

  it("the same spending as last month says so in the running-total sentence", async () => {
    routes({ summary: () => ok(fx.summary("44050.00", "0.00")) });
    renderWithQuery(<Home />);
    expect(await screen.findByRole("img", { name: /the same as by 16 Sept\./ })).toBeInTheDocument();
  });

  it("a failing running total can be retried", async () => {
    let n = 0;
    routes({ series: () => (++n <= 2 ? fail(400, "VALIDATION_003", "Series broke") : ok(fx.monthSoFar())) });
    const { user } = renderWithQuery(<Home />);
    const running = await screen.findByRole("region", { name: "Running total" });
    await user.click(await within(running).findByRole("button", { name: "Try again" }));
    expect(await within(running).findByRole("img")).toBeInTheDocument();
  });

  it("a week or a day period uses the matching ranges", async () => {
    nav.search = new URLSearchParams("p=today");
    const asked = routes();
    renderWithQuery(<Home />);
    await screen.findByText("₹48,250");
    expect(asked.summary).toEqual(expect.arrayContaining(["?from=2026-10-16&to=2026-10-16", "?from=2026-10-09&to=2026-10-09"]));
    expect(screen.getByText("Same as last Friday")).toBeInTheDocument(); // both days are ₹48,250 in this fixture
    expect(screen.getByRole("region", { name: "This week" })).toBeInTheDocument();
  });

  it("an invalid URL falls back to this month and says why", async () => {
    nav.search = new URLSearchParams("p=year");
    routes();
    renderWithQuery(<Home />);
    expect(await screen.findByRole("status")).toHaveTextContent("Showing October 2026: 'year' isn't a period.");
  });

  it("same spending as before says so; a negative net shows a minus", async () => {
    routes({ summary: () => ok(fx.summary("44050.00", "1000.00")) });
    renderWithQuery(<Home />);
    expect(await screen.findByText("Same as by 16 Sept")).toBeInTheDocument();
    expect(screen.getByText("Received ₹1,000 · Net −₹43,050")).toBeInTheDocument();
  });

  it("a new user sees how to bring money in", async () => {
    routes({ summary: () => ok(fx.summary("0.00", "0.00", { spendCount: 0 })), latest: () => ok([]), reviews: () => ok([]) });
    renderWithQuery(<Home />);
    const empty = await screen.findByRole("region", { name: "No transactions yet" });
    expect(within(empty).getByRole("link", { name: "Import a bank statement" })).toHaveAttribute("href", "/import");
    expect(within(empty).getByRole("link", { name: "Connect your SMS" })).toHaveAttribute("href", "/settings");
    expect(screen.queryByRole("link", { name: /to review/ })).not.toBeInTheDocument();
  });

  it("an empty past period suggests going further back", async () => {
    nav.search = new URLSearchParams("p=month&d=2025-01-10");
    routes({ summary: () => ok(fx.summary("0.00", "0.00", { spendCount: 0 })), latest: () => ok([]) });
    renderWithQuery(<Home />);
    expect(await screen.findByText(/Use ◀ to look further back/)).toBeInTheDocument();
  });

  it("empty sections say so in words", async () => {
    routes({ categories: () => ok({ period: {}, categories: [] }), merchants: () => ok({ period: {}, merchants: [] }), latest: () => ok([fx.transaction()]), bills: () => ok([]), home: () => ok(fx.home({ actionItems: [] })) });
    renderWithQuery(<Home />);
    expect(await screen.findByText("Nothing spent in this period.")).toBeInTheDocument();
    expect(screen.getByText("No merchants in this period.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Coming up" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Needs attention" })).not.toBeInTheDocument();
  });

  it("no transactions in a busy period's latest list says so", async () => {
    routes({ latest: () => ok([]) });
    renderWithQuery(<Home />);
    expect(await screen.findByText("No transactions in this period.")).toBeInTheDocument();
  });

  it("one failing section shows its own retry while the others still show", async () => {
    let n = 0;
    routes({ categories: () => (++n === 1 ? fail(500, "SERVER_001", "Categories unavailable") : ok(fx.categories())) });
    const { user } = renderWithQuery(<Home />);
    const where = await screen.findByRole("region", { name: "Where it went" });
    expect(await within(where).findByText(/Categories unavailable/)).toBeInTheDocument();
    expect(screen.getByText("₹48,250")).toBeInTheDocument();
    await user.click(within(where).getByRole("button", { name: "Try again" }));
    expect(await within(where).findByText("Shopping")).toBeInTheDocument();
  });

  it.each(["series", "merchants", "latest"] as const)("%s failing is contained and retryable", async (what) => {
    if (what === "series") nav.search = new URLSearchParams("p=week");
    let n = 0;
    routes({ [what]: () => (++n === 1 ? fail(400, "VALIDATION_003", `${what} broke`) : what === "series" ? ok(fx.week()) : what === "merchants" ? ok(fx.merchants()) : ok([fx.transaction()])) });
    const { user } = renderWithQuery(<Home />);
    const alert = await screen.findByText(new RegExp(`${what} broke`));
    await user.click(within(alert.closest("section")!).getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("₹48,250")).toBeInTheDocument();
    await vi.waitFor(() => expect(screen.queryByText(new RegExp(`${what} broke`))).not.toBeInTheDocument());
  });

  it("a failing headline can be retried", async () => {
    let n = 0;
    routes({ summary: () => (++n <= 2 ? fail(400, "VALIDATION_003", "Summary broke") : ok(fx.summary())) });
    const { user } = renderWithQuery(<Home />);
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("₹48,250")).toBeInTheDocument();
  });

  it("bills: overdue, today and tomorrow are labelled; paid ones are not shown", async () => {
    routes({ bills: () => ok([fx.bill({ id: "1", billerName: "Late", dueDate: "2026-10-14" }), fx.bill({ id: "2", billerName: "Now", dueDate: "2026-10-16" }),
      fx.bill({ id: "3", billerName: "Soon", dueDate: "2026-10-17" }), fx.bill({ id: "4", billerName: "Done", dueDate: "2026-10-17", status: "paid" })]) });
    renderWithQuery(<Home />);
    const coming = await screen.findByRole("region", { name: "Coming up" });
    expect(within(coming).getByText(/2d overdue/)).toBeInTheDocument();
    expect(within(coming).getByText(/due today/)).toBeInTheDocument();
    expect(within(coming).getByText(/tomorrow/)).toBeInTheDocument();
    expect(within(coming).queryByText("Done")).not.toBeInTheDocument();
  });
});
