import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import AiReadMessages from "./AiReadMessages";
import ReviewPage from "./page";

describe("Review → messages read by AI (AOS-705)", () => {
  beforeEach(() => {
    nav.pathname = "/review";
  });

  function routes(list = [fx.messageProposal(), fx.messageProposal({ id: "mp-2", direction: "credit", amount: "5000.00", merchant: null, accountLast4: null, channel: "whatsapp" })], decide?: () => Response) {
    const calls: string[] = [];
    let current = [...list];
    server.use(
      http.get("/api/v1/review/messages", () => ok(current)),
      http.post("/api/v1/review/messages/:id/:action", ({ params }) => {
        calls.push(`${params.action} ${params.id}`);
        const r = decide?.();
        if (!r) current = current.filter((x) => x.id !== params.id);
        return r ?? ok({ id: params.id, status: params.action === "confirm" ? "confirmed" : "dismissed" });
      }),
    );
    return calls;
  }

  it("lists each payment with its amount, direction, account, channel and model", async () => {
    routes();
    renderWithQuery(<AiReadMessages />);
    const out = await screen.findByRole("listitem", { name: "Money out read by AI: ZEPTO" });
    expect(out).toHaveTextContent("ZEPTO");
    expect(out).toHaveTextContent("−₹249.00");
    expect(out).toHaveTextContent("SMS · ··4321 · read by qwen3:4b");
    const inn = screen.getByRole("listitem", { name: "Money in read by AI: unknown payee" });
    expect(inn).toHaveTextContent("Unknown payee");
    expect(inn).toHaveTextContent("+₹5,000.00");
    expect(inn).toHaveTextContent("WhatsApp · read by qwen3:4b");
    expect(screen.getByRole("heading", { name: "Messages read by AI" })).toBeInTheDocument();
    expect(screen.getByText(/nothing counts until you add it/)).toBeInTheDocument();
  });

  it("adding one confirms it, says so and refreshes the totals", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<AiReadMessages />);
    const out = await screen.findByRole("listitem", { name: "Money out read by AI: ZEPTO" });
    await user.click(within(out).getByRole("button", { name: "Add to my money" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Added to your money");
    expect(calls).toEqual(["confirm mp-1"]);
    await vi.waitFor(() => expect(screen.queryByRole("listitem", { name: "Money out read by AI: ZEPTO" })).not.toBeInTheDocument());
  });

  it("not a payment dismisses it", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<AiReadMessages />);
    const inn = await screen.findByRole("listitem", { name: "Money in read by AI: unknown payee" });
    await user.click(within(inn).getByRole("button", { name: "Not a payment" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Left out of your totals");
    expect(calls).toEqual(["dismiss mp-2"]);
  });

  it("a refused decision is reported and the payment stays", async () => {
    routes([fx.messageProposal()], () => fail(404, "REVIEW_001", "Not found or already decided"));
    const { user } = renderWithQuery(<AiReadMessages />);
    await user.click(within(await screen.findByRole("listitem", { name: "Money out read by AI: ZEPTO" })).getByRole("button", { name: "Add to my money" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Money out read by AI: ZEPTO: Not found or already decided");
    expect(screen.getByRole("listitem", { name: "Money out read by AI: ZEPTO" })).toBeInTheDocument();
  });

  it("shows nothing when nothing waits", async () => {
    routes([]);
    const { container } = renderWithQuery(<AiReadMessages />);
    await vi.waitFor(() => expect(container).toBeEmptyDOMElement());
    expect(screen.queryByRole("heading", { name: "Messages read by AI" })).not.toBeInTheDocument();
  });

  it("a load error can be retried", async () => {
    let broken = true;
    server.use(http.get("/api/v1/review/messages", () => (broken ? fail(500, "SERVER_001", "List broke") : ok([fx.messageProposal()]))));
    const { user } = renderWithQuery(<AiReadMessages />);
    expect(await screen.findByRole("alert", {}, { timeout: 3000 })).toHaveTextContent("Messages read by AI: List broke");
    broken = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("listitem", { name: "Money out read by AI: ZEPTO" })).toBeInTheDocument();
  });

  it("a merchant name from a message is shown as text, never as HTML", async () => {
    routes([fx.messageProposal({ merchant: "<img src=x onerror=alert(1)>" })]);
    const { container } = renderWithQuery(<AiReadMessages />);
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("sits on the Review page above the suggested pairs", async () => {
    routes();
    renderWithQuery(<ReviewPage />);
    expect(await screen.findByRole("heading", { name: "Messages read by AI" })).toBeInTheDocument();
    expect(await screen.findByText("All clear — your totals are final.")).toBeInTheDocument();
  });
});
