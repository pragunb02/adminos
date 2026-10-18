import type { Transaction } from "./types";

/** Splitwise import (AOS-911, D-111): the API's shapes. Money is exact decimal strings (D-30). */
export interface SplitImportResult {
  importId: string;
  rows: number;
  alreadyImported: number;
  changed: number;
  applied: number;
  suggested: number;
  unmatched: number;
  noEffect: number;
  unreadableRows: number;
}

export type SplitEffect = "adjust_debit" | "owed_spend" | "settle_out" | "settle_in" | "none";
export type SplitState = "applied" | "suggested" | "unmatched" | "ignored" | "no_effect";

export interface SplitEntry {
  id: string;
  kind: "expense" | "payment";
  effect: SplitEffect;
  state: SplitState;
  date: string;
  description: string;
  counterparty: string | null;
  category: string | null;
  cost: string;
  currency: string;
  ownerNet: string;
  ownerShare: string;
  score: number | null;
  reason: string | null;
  transaction: Transaction | null;
}

export interface SplitSummary {
  /** Per currency: positive when friends owe the owner, negative when the owner owes. */
  balances: { currency: string; net: string }[];
  counts: Record<SplitState, number>;
}

export interface SplitImportItem {
  id: string;
  source: string;
  rows: number;
  createdAt: string;
}

const FIXED = ["date", "description", "category", "cost", "currency"];

/**
 * The people in a Splitwise export, read from its header row in the browser so the owner can say which one
 * they are (the file is sent once, with that answer). Returns null when the file isn't a Splitwise export.
 * The names stay in this page; nothing is stored in the browser.
 */
export function exportMembers(text: string): string[] | null {
  const header = firstRow(text.replace(/^\uFEFF/, ""));
  if (!header || header.length < FIXED.length + 2) return null;
  if (header.slice(0, FIXED.length).map((c) => c.trim().toLowerCase()).join() !== FIXED.join()) return null;
  const members = header.slice(FIXED.length).map((m) => m.trim());
  return members.every(Boolean) ? members : null;
}

/** The first CSV row (quoted fields, doubled quotes), or null if a quote never closes. */
function firstRow(text: string): string[] | null {
  const fields: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"' && field === "") quoted = true;
    else if (c === ",") { fields.push(field); field = ""; }
    else if (c === "\n" || c === "\r") break;
    else field += c;
  }
  if (quoted) return null;
  fields.push(field);
  return fields;
}
