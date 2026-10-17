import { getData, patchData } from "@/lib/query";

/**
 * Settings → AI features (AOS-704, D-67): each AI feature, what it sends and where, whether the server runs
 * it, and the user's own choice (off until turned on). All AI calls happen on the server; the browser only
 * reads this list and records the choice.
 */

export interface AiEvaluation {
  /** `recorded-synthetic` (hand-written answers), `recorded-real` or `live`. */
  answers: string;
  evaluatedOn: string;
  goldenRows: number;
}

export interface AiFeature {
  key: string;
  title: string;
  purpose: string;
  sends: string[];
  neverSends: string[];
  enabled: boolean;
  /** Whether the server runs it; false means it can't be turned on yet. */
  available: boolean;
  provider: string | null;
  locality: "local" | "cloud" | null;
  model: string | null;
  destination: string | null;
  evaluation: AiEvaluation | null;
  /** US dollars, exact (D-30). */
  spentThisMonthUsd: string;
}

export interface AiFeatures {
  features: AiFeature[];
  monthlyCapUsd: string;
}

export const AI_FEATURES_KEY = ["settings", "ai-features"];

export const fetchAiFeatures = () => getData<AiFeatures>("/api/v1/ai/features");

/** Records the user's choice for one feature; the server refuses to turn on one it doesn't run. */
export const setAiFeature = (key: string, enabled: boolean) => patchData<AiFeature>(`/api/v1/ai/features/${encodeURIComponent(key)}`, { enabled });
