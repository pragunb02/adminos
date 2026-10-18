import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import { dayAndDate, monthEndDate, shortDate, type ForecastPart } from "@/lib/forecast";
import ForecastCard from "./ForecastCard";

const ready = fx.forecast();
const monthEnd = ready.monthEnd!;
const salary = ready.salary!;
const forecastWith = (over: Parameters<typeof fx.forecast>[0]) => server.use(http.get("/api/v1/forecast", () => ok(fx.forecast(over))));

/** Home → Month-end forecast (AOS-714). */
describe("ForecastCard", () => {
  it("shows the forecast, its range, the parts as bars and how it adds up", async () => {
    forecastWith({});
    const { user } = renderWithQuery(<ForecastCard />);
    const card = await screen.findByRole("region", { name: "Month-end forecast" });
    expect(await within(card).findByText("About ₹61,540.50")).toBeInTheDocument();
    expect(within(card).getByText("By 31 Oct, at this pace")).toBeInTheDocument();
    expect(within(card).getByText("Likely ₹57,200 to ₹66,900 (8 in 10, from your last 7 months)")).toBeInTheDocument();
    const bars = within(card).getByRole("list", { name: "Parts of the forecast" });
    expect(within(bars).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "Spent so far₹48,250", "Bills₹2,350.50", "Renewals₹649", "Everyday spending₹10,291",
    ]);
    await user.click(within(card).getAllByText("How we got this")[0]);
    expect(within(card).getByText("Spent so far (1 Oct–16 Oct): ₹48,250")).toBeInTheDocument();
    expect(within(card).getByText(/\+ Everyday spending: ₹10,291 \(₹550 a weekday × 11 \+ ₹1,060.25 a weekend day × 4, learnt from your typical week 22 Aug–16 Oct\)/)).toBeInTheDocument();
    expect(within(card).getByText("+ AutoPay & mandates: none known")).toBeInTheDocument();
    expect(within(card).getAllByText(/Tata Power ₹2,350.50 on 25 Oct/)[0]).toBeInTheDocument();
    expect(within(card).getByText("= About ₹61,540.50 by 31 Oct")).toBeInTheDocument();
  });

  it("shows money left until the salary with its working", async () => {
    forecastWith({});
    const { user } = renderWithQuery(<ForecastCard />);
    expect(await screen.findByText("₹37,418.75 left from this salary")).toBeInTheDocument();
    expect(screen.getByText("Salary expected Fri, 30 Oct (ACME TECHNOLOGIES)")).toBeInTheDocument();
    expect(screen.getByText("Likely ₹33,000 to ₹41,100")).toBeInTheDocument();
    await user.click(screen.getAllByText("How we got this")[1]);
    expect(screen.getByText("Last salary: ₹1,00,000 on 30 Sept")).toBeInTheDocument();
    expect(screen.getByText("− Spent since 30 Sept: ₹52,100")).toBeInTheDocument();
    expect(screen.getByText("− Expected 17 Oct–29 Oct: ₹10,481.25")).toBeInTheDocument();
    expect(screen.getByText(/of which Everyday spending: ₹8,130.75/)).toBeInTheDocument();
    expect(screen.getByText("= ₹37,418.75 left")).toBeInTheDocument();
  });

  it("starts money left from recent bank balances and lists the ones not counted (AOS-720)", async () => {
    forecastWith({ salary: { ...salary, untilSalary: fx.balanceUntilSalary() } });
    const { user } = renderWithQuery(<ForecastCard />);
    expect(await screen.findByText("₹27,619.25 left before it comes")).toBeInTheDocument();
    await user.click(screen.getAllByText("How we got this")[1]);
    expect(screen.getByText(/In your accounts: ₹41,300.50/)).toHaveTextContent(
      "In your accounts: ₹41,300.50 (HDFC ••1234 ₹42,500.50 on 16 Oct from a bank SMS, SBI ••5678 −₹1,200 on 12 Oct from a statement)");
    expect(screen.queryByText(/Last salary/)).not.toBeInTheDocument();
    expect(screen.getByText("− Spent since 12 Oct: ₹3,200")).toBeInTheDocument();
    expect(screen.getByText("= ₹27,619.25 left")).toBeInTheDocument();
    expect(screen.getByText(/Not counted:/)).toHaveTextContent("Not counted: Account ••9012 ₹8,000 on 30 Sept (older than 7 days).");
    expect(screen.getByText(/Starts from your latest bank balances/)).toBeInTheDocument();
  });

  it("a balance from before the salary is named as such, and the salary is the start", async () => {
    const u = salary.untilSalary!;
    forecastWith({ salary: { ...salary, untilSalary: { ...u, notCounted: [{ bank: "hdfc", last4: "1234", amount: "900.00", observedOn: "2026-09-29", source: "sms", reason: "before_salary" }] } } });
    const { user } = renderWithQuery(<ForecastCard />);
    expect(await screen.findByText("₹37,418.75 left from this salary")).toBeInTheDocument();
    await user.click(screen.getAllByText("How we got this")[1]);
    expect(screen.getByText(/Not counted:/)).toHaveTextContent("HDFC ••1234 ₹900 on 29 Sept (from before the salary came)");
    expect(screen.getByText(/No bank balance from the last 7 days is known/)).toBeInTheDocument();
    expect(screen.getByText("− Spent since 30 Sept: ₹52,100")).toBeInTheDocument();
  });

  it("says short by, in words, when the forecast runs out before payday", async () => {
    forecastWith({ salary: { ...salary, untilSalary: { ...salary.untilSalary!, left: "-4200.00", leftLow: null, leftHigh: null, from: null, to: null } } });
    const { user } = renderWithQuery(<ForecastCard />);
    expect(await screen.findByText("Short by ₹4,200 before it comes")).toBeInTheDocument();
    expect(screen.queryByText(/^Likely ₹33,000/)).not.toBeInTheDocument();
    await user.click(screen.getAllByText("How we got this")[1]);
    expect(screen.getByText("= Short by ₹4,200")).toBeInTheDocument();
    expect(screen.getByText(/Expected before it \(nothing: it's due now\)/)).toBeInTheDocument();
  });

  it("explains a late salary and an unknown one instead of a number", async () => {
    forecastWith({ salary: { ...salary, status: "late", untilSalary: null, expectedOn: "2026-10-01" } });
    renderWithQuery(<ForecastCard />);
    expect(await screen.findByText(/usually comes around 1 Oct and hasn't arrived yet/)).toBeInTheDocument();
  });

  it.each([
    ["amount_varies", "Your monthly credits vary too much to plan on."],
    ["stopped", "Your regular credit hasn't come for a while."],
    [null, "No regular monthly salary found in your credits yet."],
  ] as const)("an unknown salary (%s) says why", async (reason, text) => {
    forecastWith({ salary: { ...salary, status: "unknown", reason, untilSalary: null, expectedOn: null } });
    renderWithQuery(<ForecastCard />);
    expect(await screen.findByText(`Money left until salary: ${text}`)).toBeInTheDocument();
  });

  it("a late salary without a date still reads", async () => {
    forecastWith({ salary: { ...salary, status: "late", untilSalary: null, expectedOn: null } });
    renderWithQuery(<ForecastCard />);
    expect(await screen.findByText(/usually comes around now/)).toBeInTheDocument();
  });

  it("without a range it says how many more months it needs; no salary block when absent", async () => {
    forecastWith({ monthEnd: { ...monthEnd, range: null, rangeMonthsNeeded: 1 }, salary: null });
    renderWithQuery(<ForecastCard />);
    expect(await screen.findByText("A likely range needs 1 more month of history.")).toBeInTheDocument();
    expect(screen.queryByText(/left from this salary/)).not.toBeInTheDocument();
    forecastWith({ monthEnd: { ...monthEnd, range: null, rangeMonthsNeeded: 2 } });
  });

  it("on the last day it says what was spent, and a pooled rate reads per day", async () => {
    const everyday = { ...monthEnd.parts[3], split: false, weekdayRate: "500.00", weekdays: 1, weekendDays: 0, method: "median", learntFrom: null, learntTo: null };
    forecastWith({ monthEnd: { ...monthEnd, daysLeft: 0, range: { ...monthEnd.range!, months: 3 }, rangeMonthsNeeded: 0, parts: [everyday], total: "48750.00" }, salary: null });
    const { user } = renderWithQuery(<ForecastCard />);
    expect(await screen.findByText("Spent this month, by 31 Oct")).toBeInTheDocument();
    await user.click(screen.getByText("How we got this"));
    expect(screen.getByText("+ Everyday spending: ₹10,291 (₹500 a day × 1)")).toBeInTheDocument();
  });

  it("with too little history it says so and when it starts", async () => {
    renderWithQuery(<ForecastCard />);
    expect(await screen.findByText(/Not enough history to forecast yet \(40 of 90 days\), so there's no guess. It starts on 25 Nov./)).toBeInTheDocument();
  });

  it("with no history at all it says how to start", async () => {
    forecastWith({ status: "not_enough_history", history: null, monthEnd: null, salary: null });
    renderWithQuery(<ForecastCard />);
    expect(await screen.findByText(/Not enough history to forecast yet, so there's no guess. Import a statement or connect SMS to start./)).toBeInTheDocument();
  });

  it("a failed load shows the error with a retry that works", async () => {
    server.use(http.get("/api/v1/forecast", () => fail(500, "SERVER_001", "Forecast is down")));
    const { user } = renderWithQuery(<ForecastCard />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Forecast is down");
    forecastWith({});
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("About ₹61,540.50")).toBeInTheDocument();
  });

  it("names from emails and SMS (payer, bills) are shown as text, never as HTML", async () => {
    const evil = '<img src=x onerror="alert(1)">';
    const bills = { ...monthEnd.parts[0], items: [{ ...monthEnd.parts[0].items[0], name: `${evil}<script>alert(2)</script>` }] };
    forecastWith({ monthEnd: { ...monthEnd, parts: [bills, ...monthEnd.parts.slice(1)] }, salary: { ...salary, payer: evil } });
    const { user } = renderWithQuery(<ForecastCard />);
    expect(await screen.findByText(`Salary expected Fri, 30 Oct (${evil})`)).toBeInTheDocument();
    await user.click(screen.getAllByText("How we got this")[0]);
    expect(screen.getAllByText(/<script>alert\(2\)<\/script>/).length).toBeGreaterThan(0);
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelector("script")).toBeNull();
  });

  describe("bills not arrived yet (AOS-721)", () => {
    const projected: ForecastPart = {
      ...monthEnd.parts[0], kind: "projected_bills", amount: "2140.00",
      items: [
        { name: "Sample Power Co", date: "2026-10-24", amount: "1240.00", overdue: false, billType: "electricity", monthsSeen: 4, seenFrom: "2026-06" },
        { name: "Example Mobile", date: "2026-10-27", amount: "900.00", overdue: false, billType: "mobile", monthsSeen: 1, seenFrom: "2026-09" },
      ],
    };
    const withProjected = (p: ForecastPart) => forecastWith({
      monthEnd: { ...monthEnd, total: "63680.50", parts: [monthEnd.parts[0], p, ...monthEnd.parts.slice(1)] },
      salary: { ...salary, untilSalary: { ...salary.untilSalary!, parts: [...salary.untilSalary!.parts, p] } },
    });

    it("shows them as their own part, a bar and a line with each bill's usual amount and day", async () => {
      withProjected(projected);
      const { user } = renderWithQuery(<ForecastCard />);
      const card = await screen.findByRole("region", { name: "Month-end forecast" });
      const bars = await within(card).findByRole("list", { name: "Parts of the forecast" });
      expect(within(bars).getAllByRole("listitem").map((li) => li.textContent)).toContain("Bills not arrived yet₹2,140");
      await user.click(within(card).getAllByText("How we got this")[0]);
      expect(within(card).getByText("+ Bills not arrived yet: ₹2,140")).toBeInTheDocument();
      const [monthItems, salaryItems] = within(card).getAllByRole("list", { name: "Bills not arrived yet" });
      expect(within(monthItems).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
        "Sample Power Co · expected 24 Oct · ₹1,240 · usual amount, billed 4 months running",
        "Example Mobile · expected 27 Oct · ₹900 · usual amount, billed 1 month running",
      ]);
      expect(within(salaryItems).getAllByRole("listitem")).toHaveLength(2);
      expect(within(card).getByText("of which Bills not arrived yet: ₹2,140")).toBeInTheDocument();
      expect(within(card).getByText("= About ₹63,680.50 by 31 Oct")).toBeInTheDocument();
    });

    it("without a months count it shows only the name, day and amount; none known when empty", async () => {
      withProjected({ ...projected, items: [{ ...projected.items[0], billType: null, monthsSeen: null, seenFrom: null }] });
      const { user } = renderWithQuery(<ForecastCard />);
      await user.click((await screen.findAllByText("How we got this"))[0]);
      expect(screen.getAllByText("Sample Power Co · expected 24 Oct · ₹1,240")[0]).toBeInTheDocument();
    });

    it("an empty projection reads none known and is left out of money left", async () => {
      withProjected({ ...projected, amount: "0.00", items: [] });
      const { user } = renderWithQuery(<ForecastCard />);
      await user.click((await screen.findAllByText("How we got this"))[0]);
      expect(screen.getByText("+ Bills not arrived yet: none known")).toBeInTheDocument();
      expect(screen.queryByText(/of which Bills not arrived yet/)).not.toBeInTheDocument();
      expect(screen.queryByRole("list", { name: "Bills not arrived yet" })).not.toBeInTheDocument();
    });

    it("a hostile biller name is shown as literal text, never as HTML", async () => {
      const evil = "<img src=x onerror=alert(1)>";
      withProjected({ ...projected, items: [{ ...projected.items[0], name: evil }] });
      const { user } = renderWithQuery(<ForecastCard />);
      await user.click((await screen.findAllByText("How we got this"))[0]);
      const [monthItems] = screen.getAllByRole("list", { name: "Bills not arrived yet" });
      expect(within(monthItems).getByRole("listitem")).toHaveTextContent(`${evil} · expected 24 Oct · ₹1,240 · usual amount, billed 4 months running`);
      expect(document.querySelector("img")).toBeNull();
    });
  });

  describe("your salary (AOS-722)", () => {
    it("an expected salary has Your salary inside its working; a confirmed one says so beside the salary line", async () => {
      forecastWith({ salary: { ...salary, source: "confirmed", usualDay: 30 } });
      const { user } = renderWithQuery(<ForecastCard />);
      const line = await screen.findByText(/^Salary expected Fri, 30 Oct \(ACME TECHNOLOGIES\)/);
      expect(within(line).getByText("confirmed")).toBeInTheDocument();
      await user.click(screen.getAllByText("How we got this")[1]);
      const yours = screen.getByRole("region", { name: "Your salary" });
      expect(await within(yours).findByText(/around the 30th/)).toBeInTheDocument();
    });

    it("a detected salary has no badge", async () => {
      forecastWith({ salary: { ...salary, source: "detected", usualDay: 30 } });
      renderWithQuery(<ForecastCard />);
      expect(await screen.findByText("Salary expected Fri, 30 Oct (ACME TECHNOLOGIES)")).toBeInTheDocument();
      expect(screen.queryByText("confirmed")).not.toBeInTheDocument();
    });

    it("a late salary can be confirmed or corrected from its working", async () => {
      forecastWith({ salary: { ...salary, status: "late", untilSalary: null, expectedOn: "2026-10-01", source: "confirmed" } });
      const { user } = renderWithQuery(<ForecastCard />);
      const line = await screen.findByText(/usually comes around 1 Oct and hasn't arrived yet/);
      expect(within(line).getByText("confirmed")).toBeInTheDocument();
      await user.click(screen.getAllByText("How we got this")[1]);
      expect(await screen.findByRole("button", { name: "Correct it" })).toBeVisible();
    });

    it("an unknown salary shows Your salary in the card, and a chosen payer not seen lately says so", async () => {
      forecastWith({ salary: { ...salary, status: "unknown", reason: "not_seen", untilSalary: null, expectedOn: null, source: "confirmed" } });
      renderWithQuery(<ForecastCard />);
      const line = await screen.findByText(/^Money left until salary: We haven't seen a credit from your salary payer yet\./);
      expect(within(line).getByText("confirmed")).toBeInTheDocument();
      expect(screen.getByRole("region", { name: "Your salary" })).toBeVisible();
    });

    it("a reason it doesn't know yet still reads", async () => {
      forecastWith({ salary: { ...salary, status: "unknown", reason: "brand_new" as never, untilSalary: null, expectedOn: null } });
      renderWithQuery(<ForecastCard />);
      expect(await screen.findByText("Money left until salary: No regular monthly salary found in your credits yet.")).toBeInTheDocument();
    });
  });

  it("date helpers", () => {
    expect(monthEndDate("2028-02")).toBe("2028-02-29");
    expect(monthEndDate("2026-12")).toBe("2026-12-31");
    expect(shortDate("2026-10-31")).toBe("31 Oct");
    expect(dayAndDate("2026-11-01")).toBe("Sun, 1 Nov");
  });
});
