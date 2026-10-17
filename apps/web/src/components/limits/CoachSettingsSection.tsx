"use client";

import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CATEGORIES } from "@/lib/consolePeriod";
import { categoryLabel } from "@/lib/analytics";
import { LENSES_PATH, type LensesData } from "@/lib/leaks";
import { COACH_SETTINGS_PATH, LIMITS_PATH, limitPath, validAmount, type CoachSettings, type Limit, type LimitPeriod, type LimitsData, type NewLimit, type ScopeKind } from "@/lib/limits";
import { deleteData, errorMessage, getData, patchData, postData } from "@/lib/query";
import { LimitBar } from "./LimitsPanel";

/**
 * Settings → Spending coach (Epic E9): the coach's switches and the owner's limits (AOS-904). Every limit stays
 * editable; nothing is limited during the learning month.
 */
export default function CoachSettingsSection() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: ["coach", "settings"], queryFn: () => getData<CoachSettings>(COACH_SETTINGS_PATH) });
  const limits = useQuery({ queryKey: ["coach", "limits"], queryFn: () => getData<LimitsData>(LIMITS_PATH) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["coach"] });
  const save = useMutation({ mutationFn: (v: Partial<CoachSettings>) => patchData<CoachSettings>(COACH_SETTINGS_PATH, v), onSuccess: refresh });
  const create = useMutation({ mutationFn: (v: NewLimit) => postData<Limit>(LIMITS_PATH, v), onSuccess: refresh });
  const change = useMutation({ mutationFn: (v: { id: string; amount: string }) => patchData<Limit>(limitPath(v.id), { amount: v.amount }), onSuccess: refresh });
  const remove = useMutation({ mutationFn: (id: string) => deleteData<unknown>(limitPath(id)), onSuccess: refresh });
  const error = save.error ?? create.error ?? change.error ?? remove.error ?? settings.error ?? limits.error;

  return (
    <section id="limits" aria-label="Spending coach" className="space-y-4 rounded-lg border border-gray-200 bg-white p-4">
      <h3 className="font-semibold text-gray-900">Spending coach</h3>
      {settings.data && (
        <label className="flex min-h-11 items-center justify-between gap-3 text-sm">
          <span>Limit alerts at 50%, 80% and 100%</span>
          <input type="checkbox" className="h-5 w-5" checked={settings.data.limitAlerts} disabled={save.isPending}
            onChange={(e) => save.mutate({ limitAlerts: e.target.checked })} />
        </label>
      )}
      {limits.data && (
        <div className="space-y-3">
          <p className="text-xs font-semibold uppercase text-gray-500">Limits</p>
          {limits.data.limits.length === 0 && <p className="text-sm text-gray-500">No limits.</p>}
          <ul className="space-y-3">
            {limits.data.limits.map((l) => (
              <li key={l.id}><LimitRow limit={l} busy={change.isPending || remove.isPending} onSave={(amount) => change.mutate({ id: l.id, amount })} onDelete={() => remove.mutate(l.id)} /></li>
            ))}
          </ul>
          {limits.data.learning.ready
            ? <AddLimit busy={create.isPending} onAdd={(v) => create.mutate(v)} />
            : <p role="status" className="text-sm text-gray-600">{limits.data.learning.label}. Limits open after {limits.data.learning.daysNeeded} days.</p>}
        </div>
      )}
      {error && <p role="alert" className="text-sm text-red-700">{errorMessage(error, "Couldn't save")}</p>}
    </section>
  );
}

function LimitRow({ limit, busy, onSave, onDelete }: { limit: Limit; busy: boolean; onSave: (amount: string) => void; onDelete: () => void }) {
  const [editing, setEditing] = useState(false);
  const [amount, setAmount] = useState(limit.amount.replace(/\.00$/, ""));
  return (
    <div>
      <LimitBar limit={limit} />
      {editing ? (
        <div className="mt-1 flex flex-wrap items-center gap-2 text-sm">
          <input aria-label={`New amount for ${limit.label}`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} className="h-10 w-32 rounded-md border border-gray-300 px-2" />
          <button type="button" disabled={busy || !validAmount(amount)} onClick={() => { onSave(amount.trim()); setEditing(false); }} className="h-10 rounded-md bg-blue-700 px-3 text-white disabled:opacity-40">Save</button>
          <button type="button" onClick={() => setEditing(false)} className="h-10 rounded-md border border-gray-300 px-3">Cancel</button>
        </div>
      ) : (
        <div className="mt-1 flex gap-3 text-xs">
          <button type="button" onClick={() => setEditing(true)} className="underline" aria-label={`Change ${limit.label} ${limit.period} limit`}>Change</button>
          <button type="button" disabled={busy} onClick={onDelete} className="text-red-700 underline" aria-label={`Delete ${limit.label} ${limit.period} limit`}>Delete</button>
        </div>
      )}
    </div>
  );
}

function AddLimit({ busy, onAdd }: { busy: boolean; onAdd: (v: NewLimit) => void }) {
  const lenses = useQuery({ queryKey: ["coach", "lenses"], queryFn: () => getData<LensesData>(LENSES_PATH) });
  const [kind, setKind] = useState<ScopeKind>("lens");
  const [value, setValue] = useState("");
  const [period, setPeriod] = useState<LimitPeriod>("weekly");
  const [amount, setAmount] = useState("");
  const ok = value.trim() !== "" && validAmount(amount);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!ok) return;
    onAdd({ scope: { kind, value: value.trim() }, period, amount: amount.trim() });
    setValue(""); setAmount("");
  };
  return (
    <form onSubmit={submit} className="grid gap-2 text-sm sm:grid-cols-5">
      <select aria-label="Limit on" value={kind} onChange={(e) => { setKind(e.target.value as ScopeKind); setValue(""); }} className="h-10 rounded-md border border-gray-300 bg-white px-2">
        <option value="lens">A habit</option>
        <option value="merchant">A merchant</option>
        <option value="category">A category</option>
      </select>
      {kind === "merchant" ? (
        <input aria-label="Merchant" value={value} maxLength={255} onChange={(e) => setValue(e.target.value)} placeholder="As in Activity" className="h-10 rounded-md border border-gray-300 px-2" />
      ) : (
        <select aria-label={kind === "lens" ? "Habit" : "Category"} value={value} onChange={(e) => setValue(e.target.value)} className="h-10 rounded-md border border-gray-300 bg-white px-2">
          <option value="">Choose…</option>
          {kind === "lens"
            ? (lenses.data?.lenses ?? []).map((l) => <option key={l.id} value={l.id}>{l.label}</option>)
            : CATEGORIES.filter((c) => c !== "transfer").map((c) => <option key={c} value={c}>{categoryLabel(c)}</option>)}
        </select>
      )}
      <select aria-label="Period" value={period} onChange={(e) => setPeriod(e.target.value as LimitPeriod)} className="h-10 rounded-md border border-gray-300 bg-white px-2">
        <option value="weekly">Weekly</option>
        <option value="monthly">Monthly</option>
      </select>
      <input aria-label="Amount in rupees" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="₹" className="h-10 rounded-md border border-gray-300 px-2" />
      <button type="submit" disabled={busy || !ok} className="h-10 rounded-md bg-blue-700 px-3 font-semibold text-white disabled:opacity-40">Add limit</button>
    </form>
  );
}
