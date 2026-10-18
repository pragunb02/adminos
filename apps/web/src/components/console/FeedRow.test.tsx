import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { fx } from "../../../test/fixtures";
import FeedRow from "./FeedRow";

describe("FeedRow (AOS-911 additions)", () => {
  it("a split bill shows the whole amount and the owner's share", () => {
    render(<ul><FeedRow txn={fx.transaction({ amount: "4000.00", ownShare: "1000.00" })} zone="Asia/Kolkata" /></ul>);
    expect(screen.getByRole("listitem")).toHaveTextContent("−₹4,000");
    expect(screen.getByRole("listitem")).toHaveTextContent("your share ₹1,000");
  });

  it("a settle-up says so, and an unsplit spend has no share line", () => {
    render(<ul><FeedRow txn={fx.transaction({ id: "a", type: "credit", nature: "settlement", amount: "1500.00" })} zone="Asia/Kolkata" />
      <FeedRow txn={fx.transaction({ id: "b" })} zone="Asia/Kolkata" /></ul>);
    const [settle, plain] = screen.getAllByRole("listitem");
    expect(settle).toHaveTextContent("Settle-up");
    expect(plain).not.toHaveTextContent("your share");
  });
});
