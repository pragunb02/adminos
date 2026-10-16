import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { makeQueryClient } from "@/lib/query";

/** Renders with a fresh query client (no cache shared between tests, no retries to wait for). */
export function renderWithQuery(ui: ReactElement) {
  const client = makeQueryClient();
  client.setDefaultOptions({ queries: { ...client.getDefaultOptions().queries, retry: false } });
  const user = userEvent.setup();
  return { user, client, ...render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>) };
}
