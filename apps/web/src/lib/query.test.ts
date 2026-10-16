import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { ApiError, deleteData, errorMessage, getData, getPage, makeQueryClient, patchData, postData } from "./query";

describe("query layer (AOS-308)", () => {
  it("unwraps data and throws ApiError with the API's code and message", async () => {
    server.use(http.get("/api/v1/ok", () => ok([1, 2])), http.get("/api/v1/bad", () => fail(400, "VALIDATION_003", "Invalid 'from'")));
    await expect(getData("/api/v1/ok")).resolves.toEqual([1, 2]);
    const e = await getData("/api/v1/bad").catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e).toMatchObject({ code: "VALIDATION_003", message: "Invalid 'from'", name: "ApiError" });
  });

  it("a failure without an error body is UNKNOWN", async () => {
    server.use(http.get("/api/v1/odd", () => HttpResponse.json({ success: false, data: null, error: null, meta: {} })));
    await expect(getData("/api/v1/odd")).rejects.toMatchObject({ code: "UNKNOWN", message: "Something went wrong" });
  });

  it("network failures become a NETWORK ApiError", async () => {
    server.use(http.get("/api/v1/down", () => HttpResponse.error()));
    await expect(getData("/api/v1/down")).rejects.toMatchObject({ code: "NETWORK", message: "Unable to connect to the server" });
  });

  it("post, patch and delete unwrap too", async () => {
    server.use(http.post("/api/v1/r", () => ok("p")), http.patch("/api/v1/r", () => ok("u")), http.delete("/api/v1/r", () => ok("d")));
    expect(await postData("/api/v1/r", {})).toBe("p");
    expect(await patchData("/api/v1/r", {})).toBe("u");
    expect(await deleteData("/api/v1/r")).toBe("d");
  });

  it("getPage carries the cursor; missing pagination means one page", async () => {
    server.use(
      http.get("/api/v1/feed", ({ request }) =>
        new URL(request.url).searchParams.get("cursor") ? ok(["c"]) : ok(["a", "b"], { cursor: "next", hasMore: true, total: 3 })),
    );
    expect(await getPage("/api/v1/feed")).toEqual({ items: ["a", "b"], cursor: "next", hasMore: true });
    expect(await getPage("/api/v1/feed?cursor=next")).toEqual({ items: ["c"], cursor: null, hasMore: false });
  });

  it("getPage of a null data list is empty", async () => {
    server.use(http.get("/api/v1/empty", () => ok(null)));
    expect((await getPage("/api/v1/empty")).items).toEqual([]);
  });

  it.each([
    ["NETWORK", true], ["SERVER_001", true], ["HTTP_503", true],
    ["VALIDATION_003", false], ["AUTH_001", false], ["TXN_001", false], ["HTTP_404", false],
  ])("%s retryable = %s", (code, retryable) => {
    expect(new ApiError(code, "x").isRetryable).toBe(retryable);
  });

  it("the client retries only retryable errors, at most twice", () => {
    const retry = makeQueryClient().getDefaultOptions().queries!.retry as (n: number, e: unknown) => boolean;
    expect(retry(0, new ApiError("NETWORK", ""))).toBe(true);
    expect(retry(1, new ApiError("SERVER_001", ""))).toBe(true);
    expect(retry(2, new ApiError("SERVER_001", ""))).toBe(false);
    expect(retry(0, new ApiError("VALIDATION_003", ""))).toBe(false);
    expect(retry(0, new Error("plain"))).toBe(false);
    expect(makeQueryClient().getDefaultOptions().mutations!.retry).toBe(false);
  });

  it("errorMessage shows an error's message, else the fallback", () => {
    expect(errorMessage(new ApiError("X", "Nope"))).toBe("Nope");
    expect(errorMessage("weird")).toBe("Something went wrong");
    expect(errorMessage(undefined, "Couldn't save")).toBe("Couldn't save");
  });
});
