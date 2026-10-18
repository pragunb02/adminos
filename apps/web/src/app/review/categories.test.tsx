import { http } from "msw";
import { act, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import type { CategoryAnswer, CategoryQuestion } from "@/lib/types";
import ReviewPage from "./page";

/** Review → Check these categories (AOS-708): one tap, Undo for 5 s, then a remembered correction. */
describe("Review: check these categories (AOS-708)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date", "setTimeout", "clearTimeout"] });
    vi.setSystemTime(new Date("2026-10-16T06:30:00Z"));
    nav.pathname = "/review";
  });
  afterEach(() => vi.useRealTimers());

  const big = fx.categoryQuestion();
  const ai = fx.categoryQuestion({ transaction: fx.transaction({ id: "cq-2", merchantName: "Bakers Dozen", category: "other", amount: "180" }), priority: 72 },
    { by: "ai", category: "food", confidence: 0.6, cues: [], reason: "Bakery chain", alternatives: [] });

  function routes(questions: CategoryQuestion[] = [big, ai], reply?: (body: { category: string; addToGolden: boolean }) => Response) {
    const sent: { id: string; category: string; addToGolden: boolean }[] = [];
    let current = [...questions];
    server.use(
      http.get("/api/v1/categories/review", () => ok(current)),
      http.post("/api/v1/categories/review/:id", async ({ params, request }) => {
        const body = (await request.json()) as { category: string; addToGolden: boolean };
        sent.push({ id: String(params.id), ...body });
        const r = reply?.(body);
        if (!r) current = current.filter((q) => q.transaction.id !== params.id);
        const answer: CategoryAnswer = { transaction: fx.transaction(), remembered: true, relabelled: 0, golden: body.addToGolden ? { outcome: "added" } : null };
        return r ?? ok(answer);
      }),
    );
    return sent;
  }

  const card = (name: string) => screen.findByRole("listitem", { name: `Category for ${name}` });

  it("lists the questions in the API's order with the guess, its confidence and why", async () => {
    routes();
    renderWithQuery(<ReviewPage />);
    const first = await card("Sai Medicos");
    expect(within(first).getByText("Looks like Health (55% sure, learned from your answers) · because of ‘medicos’")).toBeInTheDocument();
    expect(within(first).getByText("−₹1,240")).toBeInTheDocument();
    expect(within(first).getByRole("button", { name: "Yes, Health" })).toBeInTheDocument();
    expect(within(first).getByRole("button", { name: "Shopping" })).toBeInTheDocument();
    expect(within(first).getByRole("button", { name: "Other" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Check these categories" })).toBeInTheDocument();
    const items = within(screen.getByRole("region", { name: "Check these categories" })).getAllByRole("listitem");
    expect(items.map((li) => li.getAttribute("aria-label"))).toEqual(["Category for Sai Medicos", "Category for Bakers Dozen"]);
    expect(within(items[1]).getByText(/Bakery chain/)).toBeInTheDocument();
  });

  it("one tap confirms: hidden at once, sent after the 5-second Undo window as a remembered correction", async () => {
    const sent = routes();
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(within(await card("Sai Medicos")).getByRole("button", { name: "Yes, Health" }));
    expect(screen.queryByRole("listitem", { name: "Category for Sai Medicos" })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Kept Health for Sai Medicos; remembered");
    expect(sent).toEqual([]);
    await act(async () => { vi.advanceTimersByTime(5100); });
    await vi.waitFor(() => expect(sent).toEqual([{ id: "cq-1", category: "health", addToGolden: false }]));
  });

  it("one tap corrects, and Something else picks any other category", async () => {
    const sent = routes();
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(within(await card("Sai Medicos")).getByRole("button", { name: "Shopping" }));
    expect(screen.getByRole("status")).toHaveTextContent("Changed to Shopping for Sai Medicos; remembered");
    await user.selectOptions(within(await card("Bakers Dozen")).getByRole("combobox", { name: "Another category for Bakers Dozen" }), "Entertainment");
    await vi.waitFor(() => expect(sent).toEqual([{ id: "cq-1", category: "shopping", addToGolden: false }])); // the second answer sends the first
    expect(screen.getByRole("status")).toHaveTextContent("Changed to Entertainment for Bakers Dozen; remembered");
    await act(async () => { vi.advanceTimersByTime(5100); });
    await vi.waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]).toEqual({ id: "cq-2", category: "entertainment", addToGolden: false });
  });

  it("Undo brings the question back and sends nothing", async () => {
    const sent = routes();
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(within(await card("Sai Medicos")).getByRole("button", { name: "Other" }));
    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(await card("Sai Medicos")).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(6000); });
    expect(sent).toEqual([]);
  });

  it("with Keep a scrubbed copy ticked the answer adds it, and the result is said", async () => {
    const sent = routes();
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(await screen.findByRole("checkbox", { name: /keep a scrubbed copy/i }));
    await user.click(within(await card("Sai Medicos")).getByRole("button", { name: "Yes, Health" }));
    await act(async () => { vi.advanceTimersByTime(5100); });
    await vi.waitFor(() => expect(sent).toEqual([{ id: "cq-1", category: "health", addToGolden: true }]));
    expect(await screen.findByText("Kept a scrubbed copy in your local test set.")).toBeInTheDocument();
  });

  it("a payment to a person isn't copied, and says why", async () => {
    routes([big], () => ok({ transaction: fx.transaction(), remembered: true, relabelled: 0, golden: { outcome: "refused", reason: "looks_like_a_person" } }));
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(await screen.findByRole("checkbox", { name: /keep a scrubbed copy/i }));
    await user.click(within(await card("Sai Medicos")).getByRole("button", { name: "Other" }));
    await act(async () => { vi.advanceTimersByTime(5100); });
    expect(await screen.findByText("Not added to your local test set: it looks like a payment to a person.")).toBeInTheDocument();
  });

  it("an answer the API refuses is reported and the question returns", async () => {
    routes([big], () => fail(404, "TXN_001", "Transaction not found"));
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(within(await card("Sai Medicos")).getByRole("button", { name: "Yes, Health" }));
    await act(async () => { vi.advanceTimersByTime(5100); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Sai Medicos: Transaction not found");
    expect(await card("Sai Medicos")).toBeInTheDocument();
  });

  it("leaving the page sends a pending answer", async () => {
    const sent = routes();
    const { user, unmount } = renderWithQuery(<ReviewPage />);
    await user.click(within(await card("Bakers Dozen")).getByRole("button", { name: "Yes, Food" }));
    unmount();
    await vi.waitFor(() => expect(sent).toEqual([{ id: "cq-2", category: "food", addToGolden: false }]));
  });

  it("says how the learned categoriser is doing, and offers the local test set when it has rows", async () => {
    routes();
    server.use(
      http.get("/api/v1/categories/model", () => ok({ enabled: true, state: "cold_start", corrections: 8, correctionsNeeded: 12 })),
      http.get("/api/v1/categories/golden", () => ok([{ id: "g1", goldenId: "LABC", merchantRaw: "UPI-SAI MEDICOS-PAYMENT", direction: "debit", category: "health", createdAt: "2026-10-16T00:00:00Z" }])),
    );
    renderWithQuery(<ReviewPage />);
    expect(await screen.findByText(/after 12 more corrections/)).toBeInTheDocument();
    expect(await screen.findByText(/Local test set: 1 row/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Download CSV" })).toHaveAttribute("href", "/api/v1/categories/golden.csv");
  });

  it("nothing to ask hides the section; a load error can be retried", async () => {
    routes([]);
    const { unmount } = renderWithQuery(<ReviewPage />);
    expect(await screen.findByText("All clear — your totals are final.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Check these categories" })).not.toBeInTheDocument();
    unmount();

    let broken = true;
    server.use(http.get("/api/v1/categories/review", () => (broken ? fail(500, "SERVER_001", "Questions broke") : ok([big]))));
    const { user } = renderWithQuery(<ReviewPage />);
    const region = await screen.findByRole("region", { name: "Check these categories" });
    expect(within(region).getByRole("alert")).toHaveTextContent("Questions broke");
    broken = false;
    await user.click(within(region).getByRole("button", { name: "Try again" }));
    expect(await card("Sai Medicos")).toBeInTheDocument();
  });

  it("a category the model set itself is shown as set for you, and a credit as money in", async () => {
    routes([fx.categoryQuestion({ transaction: fx.transaction({ id: "cq-3", merchantName: null, type: "credit", nature: "refund", amount: "99", category: "health", categorySource: "learned" }) },
      { applied: true, confidence: 0.86, cues: [] })]);
    renderWithQuery(<ReviewPage />);
    const c = await card("Unknown payee");
    expect(within(c).getByText("Set to Health for you (86% sure, learned from your answers)")).toBeInTheDocument();
    expect(within(c).getByText("+₹99")).toBeInTheDocument();
  });
});
