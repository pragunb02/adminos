import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { sx } from "../../../test/salaryFixtures";
import { renderWithQuery } from "../../../test/render";
import type { SalaryView } from "@/lib/salary";
import SalaryConfirm from "./SalaryConfirm";

const PATH = "/api/v1/forecast/salary";
const salaryIs = (view: SalaryView) => server.use(http.get(PATH, () => ok(view)));
/** Answers PUTs with `reply` and records each body sent; a saved view is what a later GET returns, as on the API. */
const putAnswers = (reply: (body: Record<string, unknown>) => Response) => {
  const sent: Record<string, unknown>[] = [];
  server.use(http.put(PATH, async ({ request }) => {
    const b = (await request.json()) as Record<string, unknown>;
    sent.push(b);
    const res = reply(b);
    if (res.ok) { const view = ((await res.clone().json()) as { data: SalaryView }).data; salaryIs(view); }
    return res;
  }));
  return sent;
};
const region = () => screen.findByRole("region", { name: "Your salary" });

/** Home → forecast → Your salary (AOS-722). */
describe("SalaryConfirm", () => {
  it("shows the detected salary and confirming stores it, then refreshes the forecast", async () => {
    salaryIs(sx.view());
    const sent = putAnswers(() => ok(sx.view({ choice: sx.choice({ payer: "ACME TECHNOLOGIES", day: 30, amount: "100000.00", kind: "confirmed" }) })));
    const { user, client } = renderWithQuery(<SalaryConfirm />);
    client.setQueryData(["forecast"], { stale: false });
    const r = await region();
    expect(await within(r).findByText(/₹1,00,000 from ACME TECHNOLOGIES around the 30th/)).toBeInTheDocument();
    expect(within(r).getByText(/found in 6 credits\. Is that right\?/)).toBeInTheDocument();
    await user.click(within(r).getByRole("button", { name: "Yes, that's right" }));
    expect(await within(r).findByText(/Confirmed by you/)).toBeInTheDocument();
    expect(sent).toEqual([{ action: "confirm" }]);
    expect(client.getQueryState(["forecast"])?.isInvalidated).toBe(true);
    expect(within(r).queryByRole("button", { name: "Yes, that's right" })).not.toBeInTheDocument();
  });

  it("correcting sends the typed payer, day and amount, then shows Corrected by you", async () => {
    salaryIs(sx.view());
    const sent = putAnswers((b) => ok(sx.view({ choice: sx.choice({ payer: b.payer as string | null, day: b.day as number, amount: `${b.amount as string}.00` }) })));
    const { user } = renderWithQuery(<SalaryConfirm />);
    const r = await region();
    await user.click(await within(r).findByRole("button", { name: "Correct it" }));
    const form = within(r).getByRole("form", { name: "Correct your salary" });
    expect(within(form).getByRole("textbox", { name: "Payer" })).toHaveValue("ACME TECHNOLOGIES");
    expect(within(form).getByRole("textbox", { name: "Day of month" })).toHaveValue("30");
    expect(within(form).getByRole("textbox", { name: "Amount" })).toHaveValue("100000.00");
    await user.clear(within(form).getByRole("textbox", { name: "Payer" }));
    await user.type(within(form).getByRole("textbox", { name: "Payer" }), " Example Employer ");
    await user.clear(within(form).getByRole("textbox", { name: "Day of month" }));
    await user.type(within(form).getByRole("textbox", { name: "Day of month" }), "1");
    await user.clear(within(form).getByRole("textbox", { name: "Amount" }));
    await user.type(within(form).getByRole("textbox", { name: "Amount" }), "85,000");
    await user.click(within(form).getByRole("button", { name: "Save" }));
    expect(await within(r).findByText("₹85,000 from Example Employer around the 1st")).toBeInTheDocument();
    expect(within(r).getByText(/Corrected by you/)).toBeInTheDocument();
    expect(sent).toEqual([{ action: "correct", payer: "Example Employer", day: 1, amount: "85000" }]);
    expect(within(r).queryByRole("form")).not.toBeInTheDocument();
  });

  it("an empty payer is sent as no payer name", async () => {
    salaryIs(sx.view({ choice: sx.choice() }));
    const sent = putAnswers(() => ok(sx.view({ choice: sx.choice({ payer: null }) })));
    const { user } = renderWithQuery(<SalaryConfirm />);
    const r = await region();
    expect(await within(r).findByText(/₹85,000 from Example Employer Pvt Ltd around the 1st/)).toBeInTheDocument();
    await user.click(within(r).getByRole("button", { name: "Correct it" }));
    await user.clear(within(r).getByRole("textbox", { name: "Payer" }));
    await user.click(within(r).getByRole("button", { name: "Save" }));
    expect(await within(r).findByText(/₹85,000 \(no payer name\) around the 1st/)).toBeInTheDocument();
    expect(sent).toEqual([{ action: "correct", payer: null, day: 1, amount: "85000.00" }]);
  });

  it("checks the form before sending and lists every problem; cancel closes it", async () => {
    salaryIs(sx.view());
    const sent = putAnswers(() => ok(sx.view()));
    const { user } = renderWithQuery(<SalaryConfirm />);
    const r = await region();
    await user.click(await within(r).findByRole("button", { name: "Correct it" }));
    await user.clear(within(r).getByRole("textbox", { name: "Day of month" }));
    await user.type(within(r).getByRole("textbox", { name: "Day of month" }), "32");
    await user.clear(within(r).getByRole("textbox", { name: "Amount" }));
    await user.type(within(r).getByRole("textbox", { name: "Amount" }), "1e5");
    await user.click(within(r).getByRole("button", { name: "Save" }));
    const alert = within(r).getByRole("alert");
    expect(within(alert).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "Day of month: a whole number from 1 to 31.", "Amount: a rupee amount like 85000 or 85000.50.",
    ]);
    expect(within(r).getByRole("textbox", { name: "Day of month" })).toHaveAttribute("aria-invalid", "true");
    expect(sent).toEqual([]);
    await user.click(within(r).getByRole("button", { name: "Cancel" }));
    expect(within(r).queryByRole("form")).not.toBeInTheDocument();
    expect(within(r).getByRole("button", { name: "Yes, that's right" })).toBeInTheDocument();
  });

  it("the API's refusal is shown in an alert and the form stays open", async () => {
    salaryIs(sx.view());
    putAnswers(() => fail(400, "VALIDATION_001", "payer: letters, digits and punctuation only"));
    const { user } = renderWithQuery(<SalaryConfirm />);
    const r = await region();
    await user.click(await within(r).findByRole("button", { name: "Correct it" }));
    await user.click(within(r).getByRole("button", { name: "Save" }));
    expect(await within(r).findByRole("alert")).toHaveTextContent("payer: letters, digits and punctuation only");
    expect(within(r).getByRole("form", { name: "Correct your salary" })).toBeInTheDocument();
    await user.click(within(r).getByRole("button", { name: "Cancel" }));
    expect(within(r).queryByRole("alert")).not.toBeInTheDocument();
  });

  it.each([
    [409, "SALARY_001", "No salary was detected to confirm (stopped): correct it instead"],
    [429, "RATE_001", "Too many salary changes. Try again in 30 seconds."],
  ])("a %s on confirm is shown in words", async (status, code, message) => {
    salaryIs(sx.view());
    putAnswers(() => fail(status, code, message));
    const { user } = renderWithQuery(<SalaryConfirm />);
    const r = await region();
    await user.click(await within(r).findByRole("button", { name: "Yes, that's right" }));
    expect(await within(r).findByRole("alert")).toHaveTextContent(message);
  });

  it("with nothing detected and nothing chosen it says so, with no confirm, and a correction sets one", async () => {
    salaryIs({ detected: null, detectedReason: "amount_varies", choice: null });
    putAnswers((b) => ok({ detected: null, detectedReason: "amount_varies", choice: sx.choice({ payer: b.payer as string, day: 7, amount: "50000.00" }) }));
    const { user } = renderWithQuery(<SalaryConfirm />);
    const r = await region();
    expect(await within(r).findByText("We couldn't find your salary. Your monthly credits vary too much to plan on. Correct it to set one.")).toBeInTheDocument();
    expect(within(r).queryByRole("button", { name: "Yes, that's right" })).not.toBeInTheDocument();
    await user.click(within(r).getByRole("button", { name: "Correct it" }));
    expect(within(r).getByRole("textbox", { name: "Payer" })).toHaveValue("");
    await user.type(within(r).getByRole("textbox", { name: "Payer" }), "Example Employer");
    await user.type(within(r).getByRole("textbox", { name: "Day of month" }), "7");
    await user.type(within(r).getByRole("textbox", { name: "Amount" }), "50000");
    await user.click(within(r).getByRole("button", { name: "Save" }));
    expect(await within(r).findByText(/₹50,000 from Example Employer around the 7th/)).toBeInTheDocument();
  });

  it("an unknown reason still reads", async () => {
    salaryIs({ detected: null, detectedReason: "something_new", choice: null });
    renderWithQuery(<SalaryConfirm />);
    expect(await screen.findByText("We couldn't find your salary. Correct it to set one.")).toBeInTheDocument();
  });

  it("one credit reads in the singular and a payer-less salary says so", async () => {
    salaryIs(sx.view({ detected: sx.detected({ credits: 1, payer: null, usualDay: 2 }) }));
    renderWithQuery(<SalaryConfirm />);
    expect(await screen.findByText(/₹1,00,000 \(no payer name\) around the 2nd/)).toBeInTheDocument();
    expect(screen.getByText(/found in 1 credit\./)).toBeInTheDocument();
  });

  it("a failed load shows the error with a retry that works", async () => {
    server.use(http.get(PATH, () => fail(500, "SERVER_001", "Salary is down")));
    const { user } = renderWithQuery(<SalaryConfirm />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Salary is down");
    salaryIs(sx.view());
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/from ACME TECHNOLOGIES around the 30th/)).toBeInTheDocument();
  });

  it("a payer from bank messages is shown as text, never as HTML", async () => {
    const evil = "<img src=x onerror=alert(1)>";
    salaryIs(sx.view({ detected: sx.detected({ payer: evil }) }));
    const { user } = renderWithQuery(<SalaryConfirm />);
    const r = await region();
    expect(await within(r).findByText(new RegExp(`from ${evil.replace(/[()]/g, "\\$&")} around the 30th`))).toBeInTheDocument();
    await user.click(within(r).getByRole("button", { name: "Correct it" }));
    expect(within(r).getByRole("textbox", { name: "Payer" })).toHaveValue(evil);
    expect(document.querySelector("img")).toBeNull();
  });
});
