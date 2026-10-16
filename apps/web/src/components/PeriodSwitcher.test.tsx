import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { nav } from "../../test/next";
import PeriodSwitcher from "./PeriodSwitcher";

const today = "2026-10-16";

describe("PeriodSwitcher (URL state, D-47)", () => {
  it("labels the period; ▶ is disabled on the current one", () => {
    render(<PeriodSwitcher period={{ kind: "month", anchor: today }} today={today} />);
    expect(screen.getByRole("heading", { name: "October 2026" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next period" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Month" })).toHaveAttribute("aria-pressed", "true");
  });

  it("◀ pushes the previous period into the URL (so Back returns here)", async () => {
    nav.pathname = "/";
    render(<PeriodSwitcher period={{ kind: "month", anchor: today }} today={today} />);
    await userEvent.click(screen.getByRole("button", { name: "Previous period" }));
    expect(nav.router.push).toHaveBeenCalledWith("/?p=month&d=2026-09-01", { scroll: false });
  });

  it("▶ from a past month moves forward; the current month is the bare path", async () => {
    nav.pathname = "/spending";
    render(<PeriodSwitcher period={{ kind: "month", anchor: "2026-09-01" }} today={today} />);
    await userEvent.click(screen.getByRole("button", { name: "Next period" }));
    expect(nav.router.push).toHaveBeenCalledWith("/spending", { scroll: false });
  });

  it("Day / Week / Month jump to the current one of that length", async () => {
    nav.pathname = "/";
    render(<PeriodSwitcher period={{ kind: "month", anchor: "2026-08-01" }} today={today} />);
    await userEvent.click(screen.getByRole("button", { name: "Week" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/?p=week&d=2026-10-16", { scroll: false });
    await userEvent.click(screen.getByRole("button", { name: "Day" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/?p=today&d=2026-10-16", { scroll: false });
  });

  it("keeps the other URL parameters (e.g. the view)", async () => {
    nav.pathname = "/spending";
    nav.search = new URLSearchParams("view=merchants");
    render(<PeriodSwitcher period={{ kind: "month", anchor: today }} today={today} />);
    await userEvent.click(screen.getByRole("button", { name: "Previous period" }));
    expect(nav.router.push).toHaveBeenCalledWith("/spending?view=merchants&p=month&d=2026-09-01", { scroll: false });
  });
});
