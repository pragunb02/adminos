import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import ActionCard from "./ActionCard";
import ErrorMessage from "./ErrorMessage";
import LoadingSpinner from "./LoadingSpinner";
import StatusBadge from "./StatusBadge";
import GlobalError from "@/app/error";

describe("ErrorMessage", () => {
  it("shows the message; retry only when offered", async () => {
    const onRetry = vi.fn();
    const { rerender } = render(<ErrorMessage message="Nope" />);
    expect(screen.getByText("Nope")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    rerender(<ErrorMessage message="Nope" onRetry={onRetry} />);
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});

describe("LoadingSpinner", () => {
  it("has a default and a custom message", () => {
    const { rerender } = render(<LoadingSpinner />);
    expect(screen.getByText("Loading...")).toBeInTheDocument();
    rerender(<LoadingSpinner message="Loading bills..." />);
    expect(screen.getByText("Loading bills...")).toBeInTheDocument();
  });
});

describe("StatusBadge", () => {
  it("humanises the status and styles known ones", () => {
    render(<StatusBadge status="confirmed_safe" />);
    const badge = screen.getByText("confirmed safe");
    expect(badge.className).toContain("bg-green-100");
    expect(badge.className).toContain("text-xs");
  });
  it("unknown statuses are grey; md size is larger", () => {
    render(<StatusBadge status="mystery" size="md" />);
    const badge = screen.getByText("mystery");
    expect(badge.className).toContain("bg-gray-100");
    expect(badge.className).toContain("text-sm");
  });
});

describe("ActionCard", () => {
  it.each(["info", "warning", "critical"] as const)("%s severity is styled", (severity) => {
    const { container } = render(<ActionCard title="T" description="D" severity={severity} />);
    expect(container.firstElementChild!.className).toMatch(/border-l-(blue|amber|red)-400/);
  });
  it("shows the action only with both a label and a handler", async () => {
    const onAction = vi.fn();
    const { rerender } = render(<ActionCard title="Bill due" description="HDFC" actionLabel="View" />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    rerender(<ActionCard title="Bill due" description="HDFC" actionLabel="View" onAction={onAction} icon="📄" />);
    expect(screen.getByText("📄")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "View" }));
    expect(onAction).toHaveBeenCalledOnce();
  });
  it("defaults to info", () => {
    const { container } = render(<ActionCard title="T" description="D" />);
    expect(container.firstElementChild!.className).toContain("border-l-blue-400");
  });
});

describe("GlobalError (app/error.tsx)", () => {
  it("logs the error and retries on click", async () => {
    const log = vi.spyOn(console, "error").mockImplementation(() => {});
    const reset = vi.fn();
    render(<GlobalError error={new Error("boom")} reset={reset} />);
    expect(screen.getByRole("heading", { name: "Something went wrong" })).toBeInTheDocument();
    expect(log).toHaveBeenCalledWith("Unhandled error:", expect.any(Error));
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(reset).toHaveBeenCalledOnce();
  });
});
