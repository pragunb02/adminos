import { http } from "msw";
import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import LearnFormats, { Shape } from "./LearnFormats";
import ReviewPage from "./page";

describe("Review → formats AdminOS can learn (AOS-706)", () => {
  beforeEach(() => {
    nav.pathname = "/review";
  });

  function routes(list = [fx.messageTemplate(), fx.messageTemplate({ id: "mt-2", direction: "credit", channel: "whatsapp", bankCode: "hdfc", seen: 5, shape: "Rs <AMT> Cr. to A/c XX<ACCT> from <M>. Bal <BAL>" })], decide?: () => Response) {
    const calls: string[] = [];
    let current = [...list];
    server.use(
      http.get("/api/v1/review/templates", () => ok(current)),
      http.post("/api/v1/review/templates/:id/:action", ({ params }) => {
        calls.push(`${params.action} ${params.id}`);
        const r = decide?.();
        if (!r) current = current.filter((x) => x.id !== params.id);
        return r ?? ok({ id: params.id, status: params.action === "approve" ? "approved" : "rejected" });
      }),
    );
    return calls;
  }

  it("shows each format's wording with its slots named, and where it comes from", async () => {
    routes();
    renderWithQuery(<LearnFormats />);
    const out = await screen.findByRole("listitem", { name: "Money out format seen 3 times" });
    expect(out).toHaveTextContent("Card XX[last 4] charged Rs.[amount] at [payee] on [date].");
    expect(out).toHaveTextContent("Money out · SMS · seen 3 times");
    const inn = screen.getByRole("listitem", { name: "Money in format seen 5 times" });
    expect(inn).toHaveTextContent("Rs [amount] Cr. to A/c XX[last 4] from [payee]. Bal [balance]");
    expect(inn).toHaveTextContent("Money in · WhatsApp · hdfc · seen 5 times");
    expect(screen.getByRole("heading", { name: "Formats AdminOS can learn" })).toBeInTheDocument();
  });

  it("approving a format says what it means and sends it", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<LearnFormats />);
    await user.click(within(await screen.findByRole("listitem", { name: "Money out format seen 3 times" })).getByRole("button", { name: "Read it myself" }));
    expect(await screen.findByRole("status")).toHaveTextContent("AdminOS will read this format itself");
    expect(calls).toEqual(["approve mt-1"]);
    await vi.waitFor(() => expect(screen.queryByRole("listitem", { name: "Money out format seen 3 times" })).not.toBeInTheDocument());
  });

  it("keep reviewing rejects it", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<LearnFormats />);
    await user.click(within(await screen.findByRole("listitem", { name: "Money in format seen 5 times" })).getByRole("button", { name: "Keep reviewing" }));
    expect(await screen.findByRole("status")).toHaveTextContent("This format will keep going to Review");
    expect(calls).toEqual(["reject mt-2"]);
  });

  it("a refused decision is reported", async () => {
    routes([fx.messageTemplate()], () => fail(404, "REVIEW_002", "Not found or already decided"));
    const { user } = renderWithQuery(<LearnFormats />);
    await user.click(within(await screen.findByRole("listitem", { name: "Money out format seen 3 times" })).getByRole("button", { name: "Read it myself" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Format: Not found or already decided");
  });

  it("shows nothing when nothing waits, and a load error can be retried", async () => {
    routes([]);
    const { container, unmount } = renderWithQuery(<LearnFormats />);
    await vi.waitFor(() => expect(container).toBeEmptyDOMElement());
    unmount();
    let broken = true;
    server.use(http.get("/api/v1/review/templates", () => (broken ? fail(500, "SERVER_001", "List broke") : ok([fx.messageTemplate()]))));
    const { user } = renderWithQuery(<LearnFormats />);
    expect(await screen.findByRole("alert", {}, { timeout: 3000 })).toHaveTextContent("Formats to learn: List broke");
    broken = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("listitem", { name: "Money out format seen 3 times" })).toBeInTheDocument();
  });

  it("a shape is rendered as text, never as markup", () => {
    const { container } = render(<Shape shape={"<img src=x onerror=alert(1)> Rs <AMT>"} />);
    expect(container).toHaveTextContent("<img src=x onerror=alert(1)> Rs [amount]");
    expect(container.querySelector("img")).toBeNull();
  });

  it("sits on the Review page", async () => {
    routes();
    renderWithQuery(<ReviewPage />);
    expect(await screen.findByRole("heading", { name: "Formats AdminOS can learn" })).toBeInTheDocument();
  });
});
