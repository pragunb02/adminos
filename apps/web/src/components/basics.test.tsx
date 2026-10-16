import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
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
