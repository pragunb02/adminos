import { http } from "msw";
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import TransactionsPage from "./page";

describe("Transactions feed", () => {
  const record = () => {
    const urls: URL[] = [];
    server.use(http.get("/api/v1/transactions", ({ request }) => {
      const url = new URL(request.url);
      urls.push(url);
      if (url.searchParams.get("cursor") === "p2") return ok([fx.transaction({ id: "t-3", merchantName: "Page Two" })], { cursor: null, hasMore: false, total: 3 });
      return ok([
        fx.transaction({ isRecurring: true, isAnomaly: true }),
        fx.transaction({ id: "t-2", type: "credit", amount: 100000, merchantName: null, accountLast4: null, paymentMethod: null, category: "other" }),
      ], { cursor: "p2", hasMore: true, total: 3 });
    }));
    return urls;
  };

  it("lists transactions with markers, debit/credit signs and Indian grouping", async () => {
    record();
    renderWithQuery(<TransactionsPage />);
    expect(screen.getByText("Loading transactions...")).toBeInTheDocument();
    expect(await screen.findByText("Swiggy")).toBeInTheDocument();
    const swiggy = screen.getByText("Swiggy").parentElement!;
    expect(swiggy).toHaveTextContent("⚠️");
    expect(swiggy).toHaveTextContent("🔄");
    expect(screen.getByText("-₹349")).toBeInTheDocument();
    expect(screen.getByText("+₹1,00,000")).toHaveClass("text-green-600");
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(screen.getByText("••1234")).toBeInTheDocument();
  });

  it("load more appends the next page and then disappears", async () => {
    const urls = record();
    const { user } = renderWithQuery(<TransactionsPage />);
    await user.click(await screen.findByRole("button", { name: "Load more" }));
    expect(await screen.findByText("Page Two")).toBeInTheDocument();
    expect(screen.getByText("Swiggy")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    expect(urls.at(-1)!.searchParams.get("cursor")).toBe("p2");
  });

  it("every filter goes into the request and restarts paging", async () => {
    const urls = record();
    const { user } = renderWithQuery(<TransactionsPage />);
    await screen.findByText("Swiggy");
    await user.selectOptions(screen.getByLabelText("Category"), "food");
    await user.selectOptions(screen.getByLabelText("Type"), "debit");
    await user.type(screen.getByLabelText("From date"), "2026-10-01");
    await user.type(screen.getByLabelText("To date"), "2026-10-16");
    await user.click(screen.getByLabelText("Recurring only"));
    await user.type(screen.getByLabelText("Search merchant"), "swig");
    await screen.findByText("Swiggy");
    await new Promise((r) => setTimeout(r, 400)); // the 300ms search debounce
    const last = urls.at(-1)!.searchParams;
    expect(Object.fromEntries(last)).toEqual({ category: "food", type: "debit", merchant: "swig", from: "2026-10-01", to: "2026-10-16", isRecurring: "true", limit: "20" });
    expect(urls.some((u) => u.searchParams.get("merchant") === "s")).toBe(false); // debounced: not one request per key
  });

  it("an empty result says so", async () => {
    server.use(http.get("/api/v1/transactions", () => ok([], { cursor: null, hasMore: false, total: 0 })));
    renderWithQuery(<TransactionsPage />);
    expect(await screen.findByText("No transactions found")).toBeInTheDocument();
  });

  it("an error can be retried", async () => {
    let n = 0;
    server.use(http.get("/api/v1/transactions", () => (++n === 1 ? fail(400, "VALIDATION_003", "Invalid 'from'") : ok([fx.transaction()]))));
    const { user } = renderWithQuery(<TransactionsPage />);
    expect(await screen.findByText("Invalid 'from'")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Swiggy")).toBeInTheDocument();
  });
});
