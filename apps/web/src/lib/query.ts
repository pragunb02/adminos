/**
 * The web app's one data layer (AOS-308, D-45): TanStack Query on top of the API client.
 * Pages call `useQuery({ queryKey, queryFn: () => getData(path) })` instead of fetching in
 * effects. Errors arrive as [ApiError] with the API's code and message; a 401 the refresh
 * can't fix is already handled by `api` (redirect to /login).
 */

import { QueryClient } from "@tanstack/react-query";
import { api } from "./api";
import type { ApiResponse } from "./types";

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }

  /** Only network and server failures may succeed on retry; validation, not-found and auth errors won't. */
  get isRetryable(): boolean {
    return this.code === "NETWORK" || this.code.startsWith("SERVER_") || /^HTTP_5\d\d$/.test(this.code);
  }
}

function unwrap<T>(res: ApiResponse<T>): T {
  if (res.success) return res.data as T;
  throw new ApiError(res.error?.code ?? "UNKNOWN", res.error?.message ?? "Something went wrong");
}

const network = (e: unknown): never => {
  if (e instanceof ApiError) throw e;
  throw new ApiError("NETWORK", "Unable to connect to the server");
};

export const getData = <T>(path: string) => api.get<T>(path).then(unwrap, network);

/** One page of a cursor-paginated list, for `useInfiniteQuery`. */
export interface Page<T> {
  items: T[];
  cursor: string | null;
  hasMore: boolean;
}
export const getPage = <T>(path: string): Promise<Page<T>> =>
  api.get<T[]>(path).then((res) => {
    const items = unwrap(res) ?? [];
    return { items, cursor: res.pagination?.cursor ?? null, hasMore: res.pagination?.hasMore ?? false };
  }, network);
export const postData = <T>(path: string, body: unknown) => api.post<T>(path, body).then(unwrap, network);
export const patchData = <T>(path: string, body: unknown) => api.patch<T>(path, body).then(unwrap, network);
export const deleteData = <T>(path: string, body?: unknown) => api.delete<T>(path, body).then(unwrap, network);

/** The message to show for any error a query or mutation produced. */
export const errorMessage = (e: unknown, fallback = "Something went wrong") => (e instanceof Error ? e.message : fallback);

export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: true,
        retry: (failures, error) => error instanceof ApiError && error.isRetryable && failures < 2,
      },
      mutations: { retry: false },
    },
  });
}
