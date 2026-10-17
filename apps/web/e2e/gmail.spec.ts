import type { Page } from "@playwright/test";
import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";

/**
 * Connect Gmail in a real browser (AOS-406, D-64): Settings → Google → back to the callback page.
 * Google can't be reached from tests, so its consent page is replaced by a page that sends the user
 * straight back to the app with what Google would send: `code` + `state`, or `error`. (A mocked 302
 * isn't allowed in WebKit, so `location.replace` does the redirect, also leaving no history entry.)
 */
async function google(page: Page, answer: (url: URL) => Record<string, string>) {
  await page.route("https://accounts.google.com/**", async (route) => {
    const url = new URL(route.request().url());
    const back = new URL(url.searchParams.get("redirect_uri") ?? "");
    for (const [k, v] of Object.entries(answer(url))) back.searchParams.set(k, v);
    await route.fulfill({ contentType: "text/html", body: `<script>location.replace(${JSON.stringify(back.toString())})</script>` });
  });
}

/** The page's own alert: Next's route announcer is also `role="alert"`. */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

const approve = (url: URL) => ({ code: "4/0-e2e-code", state: url.searchParams.get("state") ?? "", scope: "email https://www.googleapis.com/auth/gmail.readonly" });

test.describe("Connect Gmail", () => {
  test("from Settings through Google's consent to connected, without re-posting on Back", async ({ signedIn: page, api }) => {
    let connections = [fx.connection({ id: "c-2", sourceType: "sms" })];
    api.on("GET /api/v1/connections", () => ({ data: connections }))
      .on("POST /api/v1/connections", () => {
        connections = [...connections, fx.connection({ gmailAddress: "pragun.b@gmail.com" })];
        return { status: 201, data: connections[1] };
      });
    await google(page, approve);

    await page.goto("/settings");
    await page.getByRole("button", { name: "Connect Gmail" }).click();

    await expect(page.getByRole("status")).toContainText("Gmail connected — first sync started");
    await expect(page).toHaveURL(/\/connections\/gmail\/callback\?connected=1$/);
    const posted = api.callsTo("POST", "/api/v1/connections");
    expect(posted).toHaveLength(1);
    expect(posted[0].body).toMatchObject({ sourceType: "gmail", code: "4/0-e2e-code", state: "e2e-state", redirectUri: expect.stringMatching(/\/connections\/gmail\/callback$/) });
    expect(api.callsTo("GET", "/api/v1/connections/gmail/authorize-url")).toHaveLength(1);

    await page.getByRole("link", { name: "Back to Settings" }).click();
    await expect(page.getByRole("listitem", { name: "gmail connection" })).toContainText("pragun.b@gmail.com");
    await page.goBack();
    await expect(page.getByRole("status")).toContainText("Gmail connected");
    expect(api.callsTo("POST", "/api/v1/connections")).toHaveLength(1);
    await expectAccessible(page);
  });

  test("a cancelled consent says so and connects nothing", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/connections", () => ({ data: [] }));
    await google(page, (url) => ({ error: "access_denied", state: url.searchParams.get("state") ?? "" }));

    await page.goto("/settings");
    await page.getByRole("button", { name: "Connect Gmail" }).click();

    await expect(alertOf(page)).toContainText("You didn't allow access");
    expect(api.callsTo("POST", "/api/v1/connections")).toHaveLength(0);
    await expectAccessible(page);
  });

  test("a state the API refuses is explained, and Start again goes back to Google", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/connections", () => ({ status: 400, error: { code: "CONN_010", message: "This Gmail sign-in doesn't match your session." } }));
    await google(page, approve);

    await page.goto("/connections/gmail/callback?code=planted&state=not-mine");
    await expect(alertOf(page)).toContainText("This sign-in didn't start here");

    await page.getByRole("button", { name: "Start again" }).click();
    await expect(alertOf(page)).toContainText("This sign-in didn't start here"); // the fresh answer is refused too here
    expect(api.callsTo("GET", "/api/v1/connections/gmail/authorize-url")).toHaveLength(1);
    expect(api.callsTo("POST", "/api/v1/connections")).toHaveLength(2);
  });

  test("Google sign-in not configured is said on Settings", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/connections", () => ({ data: [] }))
      .on("GET /api/v1/connections/gmail/authorize-url", () => ({ status: 503, error: { code: "CONN_009", message: "Google sign-in is not configured" } }));

    await page.goto("/settings");
    await page.getByRole("button", { name: "Connect Gmail" }).click();

    await expect(alertOf(page)).toContainText("Google sign-in isn't set up");
  });

  test("the callback needs a session: signed out it goes to the login page", async ({ page }) => {
    await page.goto("/connections/gmail/callback?code=x&state=y");
    await expect(page).toHaveURL(/\/login$/);
  });
});
