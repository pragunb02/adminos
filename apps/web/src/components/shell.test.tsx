import { http } from "msw";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { ok } from "../../test/msw/envelope";
import { nav } from "../../test/next";
import NavBar from "./NavBar";
import AppShell from "./AppShell";
import Providers from "./Providers";
import { api } from "@/lib/api";

describe("NavBar", () => {
  it("lists every section and marks the current page", () => {
    nav.pathname = "/transactions";
    render(<NavBar />);
    for (const label of ["Home", "Transactions", "Import", "Subscriptions", "Bills", "Briefings", "Settings"]) {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Transactions" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Home" })).not.toHaveAttribute("aria-current");
  });

  it("logs out", async () => {
    const logout = vi.spyOn(api, "logout").mockResolvedValue();
    render(<NavBar />);
    await userEvent.click(screen.getByRole("button", { name: "Log out" }));
    expect(logout).toHaveBeenCalledOnce();
  });
});

describe("AppShell", () => {
  it("renders the title, the content, two navs (desktop + mobile) and the cash button", () => {
    render(<AppShell><p>Page body</p></AppShell>);
    expect(screen.getByRole("heading", { name: "AdminOS" })).toBeInTheDocument();
    expect(screen.getByText("Page body")).toBeInTheDocument();
    expect(screen.getAllByRole("navigation", { name: "Main navigation" })).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Add cash spending" })).toBeInTheDocument();
  });
});

describe("Providers", () => {
  it("gives children a query client", async () => {
    server.use(http.get("/api/v1/ping", () => ok("pong")));
    const { useQuery } = await import("@tanstack/react-query");
    const { getData } = await import("@/lib/query");
    function Ping() {
      const { data } = useQuery({ queryKey: ["ping"], queryFn: () => getData<string>("/api/v1/ping") });
      return <p>{data ?? "…"}</p>;
    }
    render(<Providers><Ping /></Providers>);
    expect(await screen.findByText("pong")).toBeInTheDocument();
  });
});
