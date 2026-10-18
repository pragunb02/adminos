import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import AskPage from "./page";

/** "Ask your money" (AOS-711): ask, see the answer and how it was got, rate it; off, errors and hostile text. */
describe("Ask your money", () => {
  function routes(over: { ask?: (body: { question: string }) => Response; status?: () => Response; vote?: (body: unknown) => Response } = {}) {
    const asked: { question: string }[] = [];
    const votes: unknown[] = [];
    server.use(
      http.get("/api/v1/ask", () => over.status?.() ?? ok({ enabled: true, maxQuestionLength: 300 })),
      http.post("/api/v1/ask", async ({ request }) => {
        const body = (await request.json()) as { question: string };
        asked.push(body);
        return over.ask?.(body) ?? ok(fx.askAnswer());
      }),
      http.post("/api/v1/ask/feedback", async ({ request }) => {
        const body = await request.json();
        votes.push(body);
        return over.vote?.(body) ?? ok({ saved: true });
      }),
    );
    return { asked, votes };
  }

  it("typing a question and pressing Ask shows the answer and where it came from", async () => {
    const { asked } = routes();
    const { user } = renderWithQuery(<AskPage />);
    await user.type(await screen.findByRole("textbox", { name: "Your question" }), "  zepto this month vs last  ");
    await user.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByText("You spent ₹1,440 at Zepto this month, ₹2,352 less than last month's ₹3,792.")).toBeInTheDocument();
    expect(asked).toEqual([{ question: "zepto this month vs last" }]);
    expect(screen.getByRole("heading", { name: "Answer to “zepto this month vs last”" })).toBeInTheDocument();
    expect(screen.getByText(/every number checked against your data/)).toBeInTheDocument();
  });

  it("a suggestion asks straight away; the counter and the masking note are shown", async () => {
    const { asked } = routes();
    const { user } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Kitna kharcha hua aaj?" }));
    await screen.findByRole("region", { name: /Answer to/ });
    expect(asked).toEqual([{ question: "Kitna kharcha hua aaj?" }]);
    expect(screen.getByRole("textbox", { name: "Your question" })).toHaveValue("Kitna kharcha hua aaj?");
    expect(screen.getByText(/22\/300 characters/)).toBeInTheDocument();
    expect(screen.getByText(/card numbers are masked/)).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Your question" })).toHaveAttribute("maxLength", "300");
  });

  it("How I got this lists each step with its numbers and a link to Activity", async () => {
    routes();
    const { user } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Food vs last month" }));
    await screen.findByRole("region", { name: /Answer to/ });
    await user.click(screen.getByText("How I got this"));
    const tables = screen.getAllByRole("table", { name: "Spending at Zepto" });
    expect(tables).toHaveLength(2);
    expect(within(tables[0]).getByRole("rowheader", { name: "Zepto" })).toBeInTheDocument();
    expect(within(tables[0]).getByRole("cell", { name: "₹1,440 · 4 payments" })).toBeInTheDocument();
    expect(within(tables[1]).getByRole("cell", { name: "₹3,792" })).toBeInTheDocument();
    expect(screen.getByText(/1 Sep – 30 Sep 2026/)).toBeInTheDocument();
    const links = screen.getAllByRole("link", { name: "See these payments in Activity" });
    expect(links[1]).toHaveAttribute("href", "/transactions?from=2026-09-01&to=2026-09-30&q=Zepto");
  });

  it("a templated answer shows its notice; a skipped step says why; a decline has no steps", async () => {
    let n = 0;
    routes({
      ask: () => ++n === 1
        ? ok(fx.askAnswer({ source: "template", notice: "I couldn't answer that reliably in my own words, so here are the numbers from your data.", steps: [{ ...fx.askAnswer().steps[0], period: null, result: null, skipped: "future_period" }] }))
        : ok(fx.askAnswer({ askId: "a-2", source: "declined", answer: "I can only look at your own money, not anyone else's.", steps: [], comparisons: [] })),
    });
    const { user } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Top 5 merchants this month" }));
    expect(await screen.findByText(/couldn't answer that reliably/)).toBeInTheDocument();
    expect(screen.getByText("Straight from your data")).toBeInTheDocument();
    await user.click(screen.getByText("How I got this"));
    expect(screen.getByText("That period hasn't started yet.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "See these payments in Activity" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Biggest spend this week" }));
    expect(await screen.findByText("I can only look at your own money, not anyone else's.")).toBeInTheDocument();
    expect(screen.queryByText("How I got this")).not.toBeInTheDocument();
    expect(screen.getByText("Not something I can look up")).toBeInTheDocument();
  });

  it("thumbs up saves feedback for that answer and shows it pressed", async () => {
    const { votes } = routes();
    const { user } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Food vs last month" }));
    const group = await screen.findByRole("group", { name: "Was this helpful?" });
    await user.click(within(group).getByRole("button", { name: "Yes" }));
    expect(await screen.findByText("Thanks, saved.")).toBeInTheDocument();
    expect(votes).toEqual([{ askId: fx.askAnswer().askId, helpful: true }]);
    expect(within(group).getByRole("button", { name: "Yes" })).toHaveAttribute("aria-pressed", "true");
    expect(within(group).getByRole("button", { name: "No" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByText(/personal details masked/)).toBeInTheDocument();
  });

  it("a failed vote says why", async () => {
    routes({ vote: () => fail(404, "ASK_008", "That answer is too old to rate. Ask again to rate it.") });
    const { user } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Food vs last month" }));
    await user.click(await screen.findByRole("button", { name: "No" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That answer is too old to rate.");
  });

  it("an error explains itself and Try again asks the same question", async () => {
    let n = 0;
    const { asked } = routes({ ask: () => (++n === 1 ? fail(429, "RATE_001", "Too many questions. Try again in 12 minutes.") : ok(fx.askAnswer())) });
    const { user } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Food vs last month" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many questions. Try again in 12 minutes.");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByRole("region", { name: /Answer to/ });
    expect(asked.map((a) => a.question)).toEqual(["Food vs last month", "Food vs last month"]);
  });

  it("network and server failures get calm words", async () => {
    let n = 0;
    routes({ ask: () => (++n === 1 ? fail(409, "ASK_004", "Ask isn't turned on for this server.") : fail(503, "ASK_006", "This month's AI budget is used up. Ask will work again next month.")) });
    const { user } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Food vs last month" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("AI_ASK_ENABLED");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/AI budget is used up/)).toBeInTheDocument();
  });

  it("when Ask is off the form is disabled and says how to turn it on", async () => {
    const { asked } = routes({ status: () => ok({ enabled: false, maxQuestionLength: 300 }) });
    renderWithQuery(<AskPage />);
    expect(await screen.findByRole("status")).toHaveTextContent("AI_ASK_ENABLED=true");
    expect(screen.getByRole("textbox", { name: "Your question" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Ask" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Food vs last month" })).not.toBeInTheDocument();
    expect(asked).toEqual([]);
  });

  it("an empty question isn't sent", async () => {
    const { asked } = routes();
    const { user } = renderWithQuery(<AskPage />);
    const box = await screen.findByRole("textbox", { name: "Your question" });
    await user.type(box, "   ");
    expect(screen.getByRole("button", { name: "Ask" })).toBeDisabled();
    await user.type(box, "{Enter}");
    expect(asked).toEqual([]);
  });

  it("text from the server is shown as text, never as HTML", async () => {
    const evil = "<img src=x onerror=alert(1)> Store";
    routes({
      ask: () => ok(fx.askAnswer({
        answer: "<b>bold</b> answer",
        steps: [{ ...fx.askAnswer().steps[0], merchant: evil, result: { spent: null, received: null, payments: null, total: "60.00", rows: [{ label: evil, spent: "60.00", payments: 1, category: "other" }], points: [] } }],
        comparisons: [],
      })),
    });
    const { user, container } = renderWithQuery(<AskPage />);
    await user.click(await screen.findByRole("button", { name: "Food vs last month" }));
    expect(await screen.findByText("<b>bold</b> answer")).toBeInTheDocument();
    await user.click(screen.getByText("How I got this"));
    expect(screen.getByRole("rowheader", { name: evil })).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("b")).toBeNull();
  });
});
