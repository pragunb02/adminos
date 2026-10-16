import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import RootLayout, { metadata } from "./layout";

describe("RootLayout", () => {
  it("wraps pages in the providers, in English", () => {
    const html = renderToStaticMarkup(<RootLayout><p>child</p></RootLayout>);
    expect(html).toContain('<html lang="en">');
    expect(html).toContain("<p>child</p>");
    expect(metadata.title).toBe("AdminOS");
  });
});
