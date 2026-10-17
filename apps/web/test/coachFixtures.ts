import type { LeakNumbers, LeaksData, LensDefinition, LensesData, WeekdayNumbers } from "@/lib/leaks";
import { WEEKDAYS } from "@/lib/leaks";

/** Canned spending-coach data (Epic E9) for unit tests and Playwright mocks. Synthetic; India time. */

const days = (spent: Partial<Record<(typeof WEEKDAYS)[number], [number, string]>> = {}): WeekdayNumbers[] =>
  WEEKDAYS.map((day) => ({ day, orders: spent[day]?.[0] ?? 0, spent: spent[day]?.[1] ?? "0.00" }));

export const numbers = (over: Partial<LeakNumbers> = {}): LeakNumbers => ({
  spent: "0.00", orders: 0, averageOrder: null, lateNight: { orders: 0, timedOrders: 0, share: null, spent: "0.00" },
  weekdays: days(), previousSpent: "0.00", changePct: null, ...over,
});

const lens = (id: string, label: string, description: string, n: LeakNumbers, merchants: { merchant: string; numbers: LeakNumbers }[] = []) =>
  ({ id, label, description, numbers: n, merchants });

export const cx = {
  /** 12–18 Oct 2026: quick commerce ₹1,200 (2 of 3 late), food ₹750, shopping refunds only. */
  leaks: (over: Partial<LeaksData> = {}): LeaksData => ({
    period: { from: "2026-10-12", to: "2026-10-18" },
    previousPeriod: { from: "2026-10-05", to: "2026-10-11" },
    lateNight: { from: "22:00", to: "05:00" },
    total: numbers({ spent: "951.00", orders: 5, averageOrder: "410.00", previousSpent: "500.00", changePct: "90.2" }),
    lenses: [
      lens("quick_commerce", "Quick commerce", "10-minute delivery apps",
        numbers({ spent: "1200.00", orders: 3, averageOrder: "433.33", lateNight: { orders: 2, timedOrders: 3, share: "0.6667", spent: "900.00" },
          weekdays: days({ mon: [1, "400.00"], tue: [1, "600.00"], wed: [1, "300.00"], thu: [0, "-100.00"] }), previousSpent: "500.00", changePct: "140.0" }),
        [
          { merchant: "Zepto", numbers: numbers({ spent: "900.00", orders: 2, averageOrder: "500.00", lateNight: { orders: 1, timedOrders: 2, share: "0.5000", spent: "600.00" }, previousSpent: "500.00", changePct: "80.0" }) },
          { merchant: "Blinkit", numbers: numbers({ spent: "300.00", orders: 1, averageOrder: "300.00", lateNight: { orders: 1, timedOrders: 1, share: "1.0000", spent: "300.00" } }) },
        ]),
      lens("food_delivery", "Food delivery", "Meals ordered in",
        numbers({ spent: "750.00", orders: 2, averageOrder: "375.00", lateNight: { orders: 0, timedOrders: 1, share: "0.0000", spent: "0.00" }, weekdays: days({ fri: [1, "500.00"], sat: [1, "250.00"] }) }),
        [{ merchant: "Swiggy", numbers: numbers({ spent: "750.00", orders: 2, averageOrder: "375.00" }) }]),
      lens("nights_out", "Nights out", "Bars, pubs, clubs and events", numbers()),
      lens("pay_later", "Pay later", "Buy now, pay later repayments", numbers()),
      lens("travel", "Travel", "Flights, trains and buses", numbers()),
      lens("shopping", "Shopping", "Online and fashion shopping", numbers({ spent: "-999.00" }), [{ merchant: "Amazon", numbers: numbers({ spent: "-999.00" }) }]),
    ],
    ...over,
  }),
  emptyLeaks: (): LeaksData => ({
    ...cx.leaks(),
    total: numbers(),
    lenses: cx.leaks().lenses.map((l) => ({ ...l, numbers: numbers(), merchants: [] })),
  }),
  lens: (over: Partial<LensDefinition> = {}): LensDefinition => ({
    id: "quick_commerce", label: "Quick commerce", description: "10-minute delivery apps", brands: ["Blinkit", "Zepto"], categories: [],
    subcategories: [], keywords: [], added: [], removed: [], ...over,
  }),
  lenses: (): LensesData => ({
    version: "2026-10-17", lateNight: { from: "22:00", to: "05:00" },
    lenses: [
      cx.lens({ removed: ["Dunzo"] }),
      cx.lens({ id: "food_delivery", label: "Food delivery", brands: ["Swiggy", "Zomato"], subcategories: ["food_delivery"] }),
      cx.lens({ id: "nights_out", label: "Nights out", brands: ["Toit"], keywords: ["bar", "pub", "club", "lounge"], added: ["Rohit Sharma"] }),
      cx.lens({ id: "shopping", label: "Shopping", brands: ["Amazon"], subcategories: ["ecommerce", "fashion"] }),
      cx.lens({ id: "pay_later", label: "Pay later", brands: ["LazyPay"] }),
      cx.lens({ id: "travel", label: "Travel", brands: ["IndiGo"] }),
    ],
  }),
};
