/**
 * Exact money in the browser (D-30, AOS-611). The API sends amounts as decimal strings ("300.30");
 * they are never turned into floating-point numbers to add or format. Sums are done in paise (BigInt).
 */

const MONEY = /^(-)?(\d+)(?:\.(\d{1,2}))?$/;

/** "1234.5" → 123450n paise. Throws on anything that isn't a plain decimal amount. */
export function toPaise(amount: string): bigint {
  const m = MONEY.exec(amount.trim());
  if (!m) throw new Error(`Not a money amount: '${amount}'`);
  const paise = BigInt(m[2]) * 100n + BigInt((m[3] ?? "").padEnd(2, "0"));
  return m[1] ? -paise : paise;
}

/** 123450n → "1234.50". */
export function fromPaise(paise: bigint): string {
  const negative = paise < 0n;
  const abs = negative ? -paise : paise;
  return `${negative ? "-" : ""}${abs / 100n}.${(abs % 100n).toString().padStart(2, "0")}`;
}

/** Exact sum of amounts. */
export function addMoney(...amounts: string[]): string {
  return fromPaise(amounts.reduce((sum, a) => sum + toPaise(a), 0n));
}

/** Indian grouping on the rupee digits: 1234567 → 12,34,567. */
function groupIndian(rupees: string): string {
  if (rupees.length <= 3) return rupees;
  const last3 = rupees.slice(-3);
  const rest = rupees.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ",");
  return `${rest},${last3}`;
}

/**
 * "1234567.50" → "₹12,34,567.50"; paise are shown only when they aren't zero ("349.00" → "₹349"),
 * or always with `{ paise: true }`. A negative amount is "−₹300" (true minus sign).
 */
export function formatInr(amount: string, opts: { paise?: boolean } = {}): string {
  const paise = toPaise(amount);
  const negative = paise < 0n;
  const abs = negative ? -paise : paise;
  const rupees = groupIndian((abs / 100n).toString());
  const fraction = abs % 100n;
  const tail = opts.paise || fraction !== 0n ? `.${fraction.toString().padStart(2, "0")}` : "";
  return `${negative ? "−" : ""}₹${rupees}${tail}`;
}
