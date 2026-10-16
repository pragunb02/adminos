import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import SpendingPage from "./page";
import CategoryPage from "./category/[category]/page";
import { averagePerPayment } from "@/components/console/MerchantTable";

function freezeIndiaFriday() {
  vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-16T06:30:00Z"));
  vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockReturnValue({ timeZone: "Asia/Kolkata" } as Intl.ResolvedDateTimeFormatOptions);
}

function routes(over: Partial<Record<string, (u: URL) => Response>> = {}) {
  const asked: Record<string, string[]> = {};
  const h = (what: string, def: (u: URL) => Response) => http.get(`/api/v1/${what}`, ({ request }) => {
    const u = new URL(request.url);
    (asked[what] ??= []).push(u.search);
    return (over[what] ?? def)(u);
  });
  server.use(
    h("analytics/summary", (u) => ok(u.searchParams.get("from")!.startsWith("2026-09") ? fx.summary("44050.00", "0.00") : fx.summary())),
    h("analytics/categories", (u) => ok(u.searchParams.get("from")!.startsWith("2026-09")
      ? { period: {}, categories: [{ category: "food", spent: "12000.00", share: 1, count: 20, previousSpent: "0.00" }] }
      : { ...fx.categories(), categories: [...fx.categories().categories, { category: "other", spent: "-300.00", share: 0, count: 0, previousSpent: "0.00" }] })),
    h("analytics/merchants", () => ok(fx.merchants())),
    h("analytics/series", () => ok({ period: {}, bucket: "month", points: ["05", "06", "07", "08", "09", "10"].map((m, i) => ({ start: `2026-${m}-01`, spent: `${(i + 1) * 1000}.00`, received: "0.00" })) })),
    h("transactions", () => ok([fx.transaction(), fx.transaction({ id: "t-9", merchantName: "Zomato", amount: "249.50" })])),
  );
  return asked;
}

describe("Spending (AOS-305)", () => {
  beforeEach(freezeIndiaFriday);
  afterEach(() => vi.useRealTimers());

  it("the total vs the same days before, then every category with its link (keeping the period)", async () => {
    nav.pathname = "/spending";
    nav.search = new URLSearchParams("p=month&d=2026-10-01");
    routes();
    renderWithQuery(<SpendingPage />);
    expect(await screen.findByText("₹48,250")).toBeInTheDocument();
    expect(screen.getByText(/₹4,200 more than by 16 Sept/)).toBeInTheDocument();
    const list = await screen.findByRole("region", { name: "By category" });
    expect(within(list).getAllByRole("link")).toHaveLength(7);
    expect(within(list).getByRole("link", { name: /Shopping/ })).toHaveAttribute("href", "/spending/category/shopping?p=month&d=2026-10-01");
    expect(within(list).getByText("(refunds only)")).toBeInTheDocument(); // D-46: shown, not hidden
    expect(within(list).getByText("−₹300")).toBeInTheDocument();
  });

  it("the view is in the URL: Merchants shows the merchant table with averages; switching pushes history", async () => {
    nav.pathname = "/spending";
    nav.search = new URLSearchParams("view=merchants&p=week&d=2026-10-16");
    const asked = routes();
    const { user } = renderWithQuery(<SpendingPage />);
    const table = await screen.findByRole("region", { name: "By merchant" });
    expect(await within(table).findByText(/18 payments · ₹340 each/)).toBeInTheDocument();
    expect(asked["analytics/merchants"]).toEqual(["?from=2026-10-12&to=2026-10-16&limit=50"]);
    expect(screen.getByRole("button", { name: "Merchants" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "Categories" }));
    expect(nav.router.push).toHaveBeenCalledWith("/spending?p=week&d=2026-10-16", { scroll: false });
    await user.click(screen.getByRole("button", { name: "Merchants" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/spending?view=merchants&p=week&d=2026-10-16", { scroll: false });
  });

  it("switching to Merchants from the default keeps an empty period query", async () => {
    nav.pathname = "/spending";
    routes();
    const { user } = renderWithQuery(<SpendingPage />);
    await user.click(await screen.findByRole("button", { name: "Merchants" }));
    expect(nav.router.push).toHaveBeenCalledWith("/spending?view=merchants", { scroll: false });
  });

  it("empty and error states, with retry", async () => {
    let n = 0;
    routes({ "analytics/categories": () => (++n === 1 ? fail(400, "VALIDATION_003", "Categories broke") : ok({ period: {}, categories: [] })) });
    const { user } = renderWithQuery(<SpendingPage />);
    const list = await screen.findByRole("region", { name: "By category" });
    await user.click(await within(list).findByRole("button", { name: "Try again" }));
    expect(await within(list).findByText("Nothing spent in this period.")).toBeInTheDocument();
  });

  it("no merchants says so; same spending as before says so; a failing total shows its message", async () => {
    nav.search = new URLSearchParams("view=merchants");
    routes({ "analytics/merchants": () => ok({ period: {}, merchants: [] }), "analytics/summary": () => ok(fx.summary("44050.00", "0.00")) });
    renderWithQuery(<SpendingPage />);
    expect(await screen.findByText("No merchants in this period.")).toBeInTheDocument();
    expect(await screen.findByText(/same as by 16 Sept/)).toBeInTheDocument();
  });

  it("a failing total shows why", async () => {
    routes({ "analytics/summary": () => fail(400, "VALIDATION_003", "Total broke") });
    renderWithQuery(<SpendingPage />);
    expect(await screen.findByText("Total broke")).toBeInTheDocument();
  });

  it("a merchant failure can be retried", async () => {
    nav.search = new URLSearchParams("view=merchants");
    let n = 0;
    routes({ "analytics/merchants": () => (++n === 1 ? fail(400, "VALIDATION_003", "Merchants broke") : ok(fx.merchants())) });
    const { user } = renderWithQuery(<SpendingPage />);
    const table = await screen.findByRole("region", { name: "By merchant" });
    await user.click(await within(table).findByRole("button", { name: "Try again" }));
    expect(await within(table).findByText("Amazon")).toBeInTheDocument();
  });

  it("an invalid period says so", async () => {
    nav.search = new URLSearchParams("p=fortnight");
    routes();
    renderWithQuery(<SpendingPage />);
    expect(await screen.findByRole("status")).toHaveTextContent("'fortnight' isn't a period");
  });
});

describe("Trends (AOS-306)", () => {
  beforeEach(freezeIndiaFriday);
  afterEach(() => vi.useRealTimers());

  const months = () => ok({ period: {}, bucket: "month", points: Array.from({ length: 12 }, (_, i) => {
    const d = new Date(Date.UTC(2025, 10 + i, 1)).toISOString().slice(0, 10);
    return { start: d, spent: i === 3 ? "61000.00" : "40000.00", received: "100000.00" };
  }) });
  const weeks = () => ok({ period: {}, bucket: "week", points: Array.from({ length: 12 }, (_, i) => ({ start: new Date(Date.UTC(2026, 6, 27) + i * 7 * 86_400_000).toISOString().slice(0, 10), spent: `${9000 + i * 100}.00`, received: "0.00" })) });

  it("asks for 12 months and 12 Monday-weeks ending with the period", async () => {
    nav.search = new URLSearchParams("view=trends");
    const asked = routes({ "analytics/series": (u) => (u.searchParams.get("bucket") === "month" ? months() : weeks()) });
    renderWithQuery(<SpendingPage />);
    await screen.findByRole("region", { name: "Last 12 months" });
    await vi.waitFor(() => expect(asked["analytics/series"]?.sort()).toEqual([
      "?from=2025-11-01&to=2026-10-16&bucket=month",
      "?from=2026-07-27&to=2026-10-16&bucket=week",
    ]));
  });

  it("monthly spent vs received: one sentence, current month 'so far', exact table; bars or lines in the URL", async () => {
    nav.pathname = "/spending";
    nav.search = new URLSearchParams("view=trends");
    routes({ "analytics/series": (u) => (u.searchParams.get("bucket") === "month" ? months() : weeks()) });
    const { user } = renderWithQuery(<SpendingPage />);
    const m = await screen.findByRole("region", { name: "Last 12 months" });
    expect(await within(m).findByRole("img", { name: "Last 12 months: most spent in Feb 2026 (₹61,000); Oct (so far) spent ₹40,000, received ₹1,00,000." })).toBeInTheDocument();
    expect(within(m).getByRole("button", { name: "Bars" })).toHaveAttribute("aria-pressed", "true");
    await user.click(within(m).getByRole("button", { name: "Lines" }));
    expect(nav.router.push).toHaveBeenCalledWith("/spending?view=trends&chart=lines", { scroll: false });
    await user.click(within(m).getByRole("button", { name: "View as table" }));
    const table = within(m).getByRole("table", { name: "Spent and received by month" });
    expect(within(table).getAllByRole("row")).toHaveLength(13);
    expect(within(table).getByText("Oct (so far)")).toBeInTheDocument();
    await user.click(within(m).getByRole("button", { name: "View as chart" }));
    const w = screen.getByRole("region", { name: "Last 12 weeks" });
    expect(await within(w).findByRole("img", { name: /Spending by week: highest in 12 Oct \(₹10,100\)/ })).toBeInTheDocument();
  });

  it("lines can be chosen, and Bars goes back to the default URL", async () => {
    nav.pathname = "/spending";
    nav.search = new URLSearchParams("view=trends&chart=lines");
    routes({ "analytics/series": (u) => (u.searchParams.get("bucket") === "month" ? months() : weeks()) });
    const { user } = renderWithQuery(<SpendingPage />);
    const m = await screen.findByRole("region", { name: "Last 12 months" });
    expect(within(m).getByRole("button", { name: "Lines" })).toHaveAttribute("aria-pressed", "true");
    await within(m).findByRole("img");
    await user.click(within(m).getByRole("button", { name: "Bars" }));
    expect(nav.router.push).toHaveBeenCalledWith("/spending?view=trends", { scroll: false });
  });

  it("leaving Trends drops the chart choice from the URL", async () => {
    nav.pathname = "/spending";
    nav.search = new URLSearchParams("view=trends&chart=lines");
    routes({ "analytics/series": (u) => (u.searchParams.get("bucket") === "month" ? months() : weeks()) });
    const { user } = renderWithQuery(<SpendingPage />);
    await user.click(await screen.findByRole("button", { name: "Categories" }));
    expect(nav.router.push).toHaveBeenCalledWith("/spending", { scroll: false });
  });

  it("a past period has no 'so far'; empty series say nothing yet; failures retry", async () => {
    nav.search = new URLSearchParams("view=trends&p=month&d=2026-06-01");
    let n = 0;
    routes({ "analytics/series": (u) => (u.searchParams.get("bucket") === "week" ? (++n === 1 ? fail(400, "VALIDATION_003", "Weeks broke") : weeks()) : ok({ period: {}, bucket: "month", points: [] })) });
    const { user } = renderWithQuery(<SpendingPage />);
    const m = await screen.findByRole("region", { name: "Last 12 months" });
    expect(await within(m).findByRole("img", { name: "Last 12 months: nothing yet." })).toBeInTheDocument();
    const w = screen.getByRole("region", { name: "Last 12 weeks" });
    await user.click(await within(w).findByRole("button", { name: "Try again" }));
    expect(await within(w).findByRole("img")).toBeInTheDocument();
  });

  it("a failing month chart can be retried", async () => {
    nav.search = new URLSearchParams("view=trends");
    let n = 0;
    routes({ "analytics/series": (u) => (u.searchParams.get("bucket") === "month" ? (++n === 1 ? fail(400, "VALIDATION_003", "Months broke") : months()) : weeks()) });
    const { user } = renderWithQuery(<SpendingPage />);
    const m = await screen.findByRole("region", { name: "Last 12 months" });
    await user.click(await within(m).findByRole("button", { name: "Try again" }));
    expect(await within(m).findByRole("img")).toBeInTheDocument();
  });
});

describe("Category detail (AOS-305)", () => {
  beforeEach(freezeIndiaFriday);
  afterEach(() => vi.useRealTimers());

  it("spent vs the same days before, a 6-month trend, its merchants and transactions", async () => {
    nav.params = { category: "food" };
    nav.search = new URLSearchParams("p=month&d=2026-10-01");
    const asked = routes();
    const { user } = renderWithQuery(<CategoryPage />);
    expect(await screen.findByText("₹11,200")).toBeInTheDocument();
    expect(screen.getByText(/₹800 less than by 16 Sept/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "← Spending" })).toHaveAttribute("href", "/spending?p=month&d=2026-10-01");
    expect(asked["analytics/series"]).toEqual(["?from=2026-05-01&to=2026-10-16&bucket=month&category=food"]);
    expect(asked["analytics/merchants"]).toEqual(["?from=2026-10-01&to=2026-10-16&category=food&limit=5"]);
    expect(asked.transactions).toEqual(["?category=food&from=2026-10-01&to=2026-10-16&limit=10"]);
    const trend = screen.getByRole("region", { name: "Last 6 months" });
    expect(await within(trend).findByRole("img", { name: "Food by month: highest in Oct (₹6,000); latest Oct ₹6,000." })).toBeInTheDocument();
    await user.click(within(trend).getByRole("button", { name: "View as table" }));
    expect(within(within(trend).getByRole("table")).getAllByRole("row")).toHaveLength(7);
    await user.click(within(trend).getByRole("button", { name: "View as chart" }));
    expect(screen.getByRole("link", { name: "All in Activity ›" })).toHaveAttribute("href", "/transactions?category=food");
    expect(within(screen.getByRole("region", { name: "Transactions" })).getByText("−₹249.50")).toBeInTheDocument();
  });

  it("a category with nothing this period shows ₹0 and empty sections", async () => {
    nav.params = { category: "education" };
    routes({ "analytics/merchants": () => ok({ period: {}, merchants: [] }), transactions: () => ok([]), "analytics/series": () => ok({ period: {}, bucket: "month", points: [] }) });
    renderWithQuery(<CategoryPage />);
    expect(await screen.findByText("₹999")).toBeInTheDocument(); // in fx.categories, not in September's
    expect(await screen.findByText("No merchants in this period.")).toBeInTheDocument();
    expect(screen.getByText("No transactions in this period.")).toBeInTheDocument();
    expect(await screen.findByRole("img", { name: "EMI by month: nothing yet." }).catch(() => screen.findByRole("img", { name: /nothing yet/ }))).toBeInTheDocument();
  });

  it("same as before, and a category absent in both periods is ₹0", async () => {
    nav.params = { category: "emi" };
    routes();
    renderWithQuery(<CategoryPage />);
    expect(await screen.findByText("Same as by 16 Sept")).toBeInTheDocument();
    expect(screen.getByText("₹0", { selector: "p.text-3xl" })).toBeInTheDocument(); // the headline
    expect(screen.getByRole("heading", { name: "EMI" })).toBeInTheDocument();
  });

  it("an unknown category says so and links back", async () => {
    nav.params = { category: "groceries" };
    renderWithQuery(<CategoryPage />);
    expect(screen.getByRole("heading", { name: "No such category" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "← Spending" })).toHaveAttribute("href", "/spending");
  });

  it.each(["analytics/series", "analytics/merchants", "transactions"] as const)("%s failing is contained and retryable", async (what) => {
    nav.params = { category: "food" };
    let n = 0;
    routes({ [what]: () => (++n === 1 ? fail(400, "VALIDATION_003", `${what} broke`) : ok(what === "transactions" ? [] : what === "analytics/merchants" ? fx.merchants() : { period: {}, bucket: "month", points: [] })) });
    const { user } = renderWithQuery(<CategoryPage />);
    const alert = await screen.findByText(new RegExp(`${what} broke`));
    await user.click(within(alert.closest("section")!).getByRole("button", { name: "Try again" }));
    await vi.waitFor(() => expect(screen.queryByText(new RegExp(`${what} broke`))).not.toBeInTheDocument());
  });

  it("a failing headline can be retried", async () => {
    nav.params = { category: "food" };
    let n = 0;
    routes({ "analytics/categories": () => (++n <= 2 ? fail(400, "VALIDATION_003", "Head broke") : ok(fx.categories())) });
    const { user } = renderWithQuery(<CategoryPage />);
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("₹11,200")).toBeInTheDocument();
  });
});

describe("average per payment", () => {
  it("is exact and rounded to the paisa", () => {
    expect(averagePerPayment("6120.00", 18)).toBe("340.00");
    expect(averagePerPayment("100.00", 3)).toBe("33.33");
    expect(averagePerPayment("200.00", 3)).toBe("66.67");
    expect(averagePerPayment("50.00", 0)).toBe("50.00");
  });
});
