import { http, HttpResponse } from "msw";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import type { FieldProposals } from "@/lib/types";
import ReadFields, { patchFor, shownValue } from "./ReadFields";

const proposals = (over: Partial<FieldProposals> = {}): FieldProposals => ({
  source: "ocr", pagesRead: 1, pagesTotal: 1, truncated: false, ai: "not_needed",
  proposals: [
    { field: "expiresOn", value: "2027-03-31", context: "Valid till: 31/03/2027", label: "Valid till", from: "rules" },
    { field: "number", value: "3210", context: "Policy No.: ••••3210", label: "Policy No.", from: "rules" },
    { field: "issuer", value: "Example Mutual General Insurance Company Limited", context: "<img src=x onerror=alert(1)>Example Mutual", label: "", from: "ai" },
    { field: "amount", value: "12500.00", context: "Total Premium: Rs. 12,500.00", label: "Total premium", from: "rules" },
  ],
  ...over,
});

describe("Read fields (AOS-717)", () => {
  it("shows proposals as text, and saves only the ticked ones, the number as its last 4", async () => {
    let patched: unknown = null;
    server.use(
      http.post("/api/v1/documents/:id/read", () => ok(proposals())),
      http.patch("/api/v1/documents/:id", async ({ request }) => { patched = await request.json(); return ok(fx.document()); }),
    );
    renderWithQuery(<ReadFields doc={fx.document()} onSaved={() => {}} />);
    await userEvent.click(screen.getByRole("button", { name: "Read fields" }));
    const panel = await screen.findByRole("region", { name: /Proposed fields/ });
    expect(within(panel).getByText("Expires on: 31 Mar 2027")).toBeInTheDocument();
    expect(within(panel).getByText("Number: •••• 3210")).toBeInTheDocument();
    expect(within(panel).getByText("Amount paid: ₹12,500.00")).toBeInTheDocument();
    expect(within(panel).getByText("(suggested by AI)")).toBeInTheDocument();
    expect(within(panel).getByText(/Read from the scan/)).toBeInTheDocument();
    // untrusted text stays text: no element was created from it
    expect(panel.querySelector("img")).toBeNull();
    expect(within(panel).getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
    await userEvent.click(within(panel).getByRole("checkbox", { name: /Issued by/ }));
    await userEvent.click(screen.getByRole("button", { name: "Save selected" }));
    expect(await screen.findByRole("status")).toHaveTextContent("3 fields saved. You'll be reminded before it expires.");
    expect(patched).toEqual({ expiresOn: "2027-03-31", numberLast4: "3210", amount: "12500.00" });
  });

  it("explains an empty reading, identity documents and the AI being off", async () => {
    server.use(http.post("/api/v1/documents/:id/read", () => ok(proposals({ proposals: [], ai: "identity", truncated: true, pagesRead: 5, pagesTotal: 9 }))));
    renderWithQuery(<ReadFields doc={fx.document()} onSaved={() => {}} />);
    await userEvent.click(screen.getByRole("button", { name: "Read fields" }));
    expect(await screen.findByText(/Nothing could be read with confidence/)).toBeInTheDocument();
    expect(screen.getByText(/Identity documents are never sent to an AI model/)).toBeInTheDocument();
    expect(screen.getByText(/Only the first 5 of 9 pages were read/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save selected" })).toBeDisabled();
  });

  it("shows the server's error and saves nothing", async () => {
    let patches = 0;
    server.use(
      http.post("/api/v1/documents/:id/read", () => fail(422, "DOC_008", "The image is too large to read")),
      http.patch("/api/v1/documents/:id", () => { patches++; return HttpResponse.json({}); }),
    );
    renderWithQuery(<ReadFields doc={fx.document()} onSaved={() => {}} />);
    await userEvent.click(screen.getByRole("button", { name: "Read fields" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The image is too large to read");
    expect(patches).toBe(0);
  });

  it("formats values and builds the patch", () => {
    expect(shownValue({ field: "amount", value: "125000.5" })).toBe("₹1,25,000.50");
    expect(patchFor([{ field: "issuer", value: "X Insurance", context: "", label: "", from: "rules" }])).toEqual({ issuer: "X Insurance" });
  });
});
