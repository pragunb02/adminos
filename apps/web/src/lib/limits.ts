/** Limits (AOS-904): API types and helpers. Money is an exact decimal string (D-30). */
import { formatInr } from "./money";

export type ScopeKind = "lens" | "merchant" | "category";
export type LimitPeriod = "weekly" | "monthly";

export interface LimitStatus { periodFrom: string; periodTo: string; spent: string; used: string; left: string; reached: number[]; daysLeft: number }
export interface Limit { id: string; scope: { kind: ScopeKind; value: string }; label: string; period: LimitPeriod; amount: string; status: LimitStatus }
export interface WeekSpend { from: string; to: string; spent: string }
export interface LimitSuggestion { lens: string; label: string; period: LimitPeriod; amount: string; weeks: WeekSpend[]; median: string; stepPercent: number; roundTo: string }
export interface Learning { daysOfData: number; daysNeeded: number; ready: boolean; label: string }
export interface LimitsData { today: string; learning: Learning; limits: Limit[]; suggestions: LimitSuggestion[] }
export interface CoachSettings { limitAlerts: boolean }
export interface NewLimit { scope: { kind: ScopeKind; value: string }; period: LimitPeriod; amount: string }

export const LIMITS_PATH = "/api/v1/coach/limits";
export const COACH_SETTINGS_PATH = "/api/v1/coach/settings";
export const limitPath = (id: string) => `${LIMITS_PATH}/${encodeURIComponent(id)}`;
export const dismissSuggestionPath = (lens: string) => `${LIMITS_PATH}/suggestions/${encodeURIComponent(lens)}/dismiss`;

/** "0.6000" → 60 (whole percent, for the bar width and the words). */
export const usedPercent = (used: string) => Math.round(Number(used) * 100);

/** "60% used · ₹400 left · 4 days to go" or "₹50 over". Words, not colour (D-49). */
export function limitText(l: Limit): string {
  const pct = usedPercent(l.status.used);
  const over = l.status.left.startsWith("-");
  const days = l.status.daysLeft === 0 ? "last day" : l.status.daysLeft === 1 ? "1 day to go" : `${l.status.daysLeft} days to go`;
  return `${pct}% used · ${over ? `${formatAbs(l.status.left)} over` : `${formatAbs(l.status.left)} left`} · ${days}`;
}

const formatAbs = (amount: string) => formatInr(amount.startsWith("-") ? amount.slice(1) : amount);

/** A rupee amount the API accepts: up to 8 digits and 2 decimals. */
export const AMOUNT = /^\d{1,8}(\.\d{1,2})?$/;
export const validAmount = (s: string) => AMOUNT.test(s.trim()) && Number(s) > 0;
