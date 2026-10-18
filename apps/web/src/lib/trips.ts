/** Display helpers for trips (AOS-910). Dates are calendar dates from the API (the user's own), never shifted. */

const fmt = (iso: string, opts: Intl.DateTimeFormatOptions) => new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-IN", { ...opts, timeZone: "UTC" });

/** "24 Oct – 30 Oct", or "30 Dec 2026 – 2 Jan 2027" across years. */
export function tripDates(start: string, end: string): string {
  const sameYear = start.slice(0, 4) === end.slice(0, 4);
  const short = { day: "numeric", month: "short" } as const;
  const long = { day: "numeric", month: "short", year: "numeric" } as const;
  return sameYear ? `${fmt(start, short)} – ${fmt(end, short)}` : `${fmt(start, long)} – ${fmt(end, long)}`;
}

const MODE = { flight: "Flight", train: "Train", bus: "Bus" } as const;

/** "Flight · Train", or "Travel" when the booking doesn't say (an agency). */
export const modesLabel = (modes: (keyof typeof MODE)[]) => (modes.length === 0 ? "Travel" : modes.map((m) => MODE[m]).join(" · "));

export const ROLE = { booking: "Booking", spend: "Around the trip", added: "Added by you" } as const;
