/**
 * Console formatters (AOS-302, docs/design/console.md §6). Money stays an exact decimal string (D-30):
 * arithmetic is in paise (lib/money.ts); only display rounding happens here.
 */
import { formatInr, fromPaise, toPaise } from "./money";

/** ₹ on chart axes and small tiles: ₹950 · ₹1.2K · ₹12.3L · ₹1.2Cr (Indian units; one decimal, trailing .0 dropped). */
export function compactInr(amount: string): string {
  const paise = toPaise(amount);
  const negative = paise < 0n;
  const rupees = (negative ? -paise : paise) / 100n;
  const units: [bigint, string][] = [
    [10_000_000n, "Cr"],
    [100_000n, "L"],
    [1_000n, "K"],
  ];
  const sign = negative ? "−" : "";
  for (const [size, suffix] of units) {
    if (rupees >= size) {
      const tenths = (rupees * 10n + size / 2n) / size; // rounded to one decimal
      const whole = tenths / 10n;
      const fraction = tenths % 10n;
      return `${sign}₹${whole}${fraction === 0n ? "" : `.${fraction}`}${suffix}`;
    }
  }
  return `${sign}₹${rupees}`;
}

export type Direction = "up" | "down" | "same";

/** The change from `previous` to `current`: arrow + amount in words, never colour alone. */
export function delta(current: string, previous: string): { direction: Direction; amount: string; text: string } {
  const diff = toPaise(current) - toPaise(previous);
  const direction: Direction = diff > 0n ? "up" : diff < 0n ? "down" : "same";
  const amount = fromPaise(diff < 0n ? -diff : diff);
  const text = direction === "same" ? "Same as before" : `${formatInr(amount)} ${direction === "up" ? "more" : "less"}`;
  return { direction, amount, text };
}

export const arrow = (d: Direction) => (d === "up" ? "▲" : d === "down" ? "▼" : "＝");

/** "Today" / "Yesterday" / "Mon" within a week, else "16 Oct" (+ year if not this year), in `zone`. */
export function relativeDate(iso: string, zone: string, now: Date = new Date()): string {
  const day = (d: Date) => new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(d);
  const target = new Date(iso);
  const days = Math.round((Date.parse(day(now)) - Date.parse(day(target))) / 86_400_000);
  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days > 1 && days < 7) return new Intl.DateTimeFormat("en-IN", { timeZone: zone, weekday: "short" }).format(target);
  const sameYear = day(target).slice(0, 4) === day(now).slice(0, 4);
  return new Intl.DateTimeFormat("en-IN", { timeZone: zone, day: "numeric", month: "short", ...(sameYear ? {} : { year: "numeric" }) }).format(target);
}

/** The browser's timezone (the user's own; the API applies their stored one, AOS-120). */
export const browserZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Kolkata";
