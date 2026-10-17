import { http, HttpResponse } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { fx } from "../../test/fixtures";
import { renderWithQuery } from "../../test/render";
import type { AiFeature } from "@/lib/ai";
import AiFeatures from "./AiFeatures";

const list = (...features: AiFeature[]) => http.get("/api/v1/ai/features", () => ok({ features, monthlyCapUsd: "2.00" }));
const card = () => screen.getByRole("listitem", { name: "Categorise unknown shops" });

describe("Settings → AI features (AOS-704)", () => {
  it("shows each feature off, with exactly what is sent, what never is, and where it goes", async () => {
    server.use(list(fx.aiFeature()));
    renderWithQuery(<AiFeatures />);
    const toggle = await screen.findByRole("switch", { name: "Categorise unknown shops" });
    expect(toggle).toHaveAttribute("aria-checked", "false");
    expect(toggle).toBeEnabled();
    expect(screen.getByText(/capped at \$2.00 a month/)).toBeInTheDocument();
    const sends = within(card()).getByRole("list", { name: "What Categorise unknown shops sends" });
    expect(within(sends).getAllByRole("listitem")).toHaveLength(3);
    expect(within(card()).getByRole("list", { name: "What Categorise unknown shops never sends" })).toHaveTextContent("Payments to people");
    expect(card()).toHaveTextContent("Runs on Anthropic's Claude (claude-haiku-5-5), a cloud service.");
    expect(card()).toHaveTextContent("Where it goes: Anthropic's Claude API: a cloud service outside India, over HTTPS.");
    expect(card()).toHaveTextContent("stand-in answers");
    expect(card()).toHaveTextContent("Spent this month: $0.00");
  });

  it("a local model says the data stays on your server, and a real eval gives its date", async () => {
    server.use(list(fx.aiFeature({ provider: "ollama", locality: "local", model: "qwen3:4b", destination: "The Ollama model on your own AdminOS server: it doesn't leave your server",
      evaluation: { answers: "live", evaluatedOn: "2026-10-17", goldenRows: 288 } })));
    renderWithQuery(<AiFeatures />);
    await screen.findByRole("switch", { name: "Categorise unknown shops" });
    expect(card()).toHaveTextContent("Runs on a local model on your own server (qwen3:4b).");
    expect(card()).toHaveTextContent("Checked on our test set (288 examples) on 2026-10-17.");
  });

  it("a feature the server doesn't run is shown off and can't be switched on", async () => {
    renderWithQuery(<AiFeatures />); // default handler: not set up
    const toggle = await screen.findByRole("switch", { name: "Categorise unknown shops" });
    expect(toggle).toBeDisabled();
    expect(card()).toHaveTextContent("Not set up on this server yet, so it stays off.");
    expect(card()).not.toHaveTextContent("Where it goes");
  });

  it("turning it on saves the choice and says so; turning it off again works", async () => {
    const sent: unknown[] = [];
    let enabled = false;
    server.use(
      list(fx.aiFeature()),
      http.patch("/api/v1/ai/features/categorization", async ({ request }) => {
        const body = (await request.json()) as { enabled: boolean };
        sent.push(body);
        enabled = body.enabled;
        return ok(fx.aiFeature({ enabled }));
      }),
    );
    const { user } = renderWithQuery(<AiFeatures />);
    const toggle = await screen.findByRole("switch", { name: "Categorise unknown shops" });
    await user.click(toggle);
    expect(await screen.findByRole("status")).toHaveTextContent("Categorise unknown shops is on.");
    expect(toggle).toHaveAttribute("aria-checked", "true");
    await user.click(toggle);
    expect(await screen.findByText("Categorise unknown shops is off.")).toBeInTheDocument();
    expect(sent).toEqual([{ enabled: true }, { enabled: false }]);
  });

  it("a refused change shows the server's reason and reloads the real state", async () => {
    let loads = 0;
    server.use(
      http.get("/api/v1/ai/features", () => { loads++; return ok({ features: [fx.aiFeature()], monthlyCapUsd: "2.00" }); }),
      http.patch("/api/v1/ai/features/categorization", () => fail(409, "AI_002", "categorization isn't set up on this server")),
    );
    const { user } = renderWithQuery(<AiFeatures />);
    await user.click(await screen.findByRole("switch", { name: "Categorise unknown shops" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("categorization isn't set up on this server");
    expect(screen.getByRole("switch", { name: "Categorise unknown shops" })).toHaveAttribute("aria-checked", "false");
    await screen.findByRole("switch", { name: "Categorise unknown shops" });
    expect(loads).toBeGreaterThanOrEqual(2);
  });

  it("a network failure on save says it couldn't connect", async () => {
    server.use(list(fx.aiFeature()), http.patch("/api/v1/ai/features/categorization", () => HttpResponse.error()));
    const { user } = renderWithQuery(<AiFeatures />);
    await user.click(await screen.findByRole("switch", { name: "Categorise unknown shops" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Unable to connect to the server");
  });

  it("a failed load can be retried", async () => {
    let fails = true;
    server.use(http.get("/api/v1/ai/features", () => (fails ? fail(500, "SERVER_001", "Something broke") : ok({ features: [fx.aiFeature()], monthlyCapUsd: "2.00" }))));
    const { user } = renderWithQuery(<AiFeatures />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Something broke");
    fails = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("switch", { name: "Categorise unknown shops" })).toBeInTheDocument();
  });

  it("no features says so", async () => {
    server.use(list());
    renderWithQuery(<AiFeatures />);
    expect(await screen.findByText("There are no AI features in this version.")).toBeInTheDocument();
  });

  it("text from the server is shown as text, never as HTML", async () => {
    const hostile = '<img src=x onerror="window.__pwned=1">Shops';
    server.use(list(fx.aiFeature({ title: hostile, sends: ["<script>window.__pwned=1</script>"], provider: "evil" })));
    const { container } = renderWithQuery(<AiFeatures />);
    expect(await screen.findByRole("switch", { name: hostile })).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    expect(screen.getByText("<script>window.__pwned=1</script>")).toBeInTheDocument();
    expect(screen.getByText(/Runs on an AI provider/)).toBeInTheDocument();
  });
});
