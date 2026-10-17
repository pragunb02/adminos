"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AI_FEATURES_KEY, fetchAiFeatures, setAiFeature, type AiFeature, type AiFeatures as AiFeaturesData } from "@/lib/ai";
import { errorMessage } from "@/lib/query";

/** Where the feature runs, in words (or why it can't be turned on). */
function servingText(f: AiFeature): string {
  if (!f.available) return "Not set up on this server yet, so it stays off.";
  const model = f.model ? ` (${f.model})` : "";
  return f.locality === "local" ? `Runs on a local model on your own server${model}.` : `Runs on ${providerName(f.provider)}${model}, a cloud service.`;
}

const providerName = (p: string | null) => (p === "anthropic" ? "Anthropic's Claude" : p === "ollama" ? "Ollama" : "an AI provider");

/** How the feature was measured before it was allowed (D-68). */
function evaluationText(f: AiFeature): string | null {
  if (!f.evaluation) return null;
  if (f.evaluation.answers === "recorded-synthetic") return "Checked on our test set with stand-in answers; not yet measured with the real model.";
  return `Checked on our test set (${f.evaluation.goldenRows} examples) on ${f.evaluation.evaluatedOn}.`;
}

/**
 * Settings → AI features (AOS-704, D-67). One card per feature: a switch (off by default) and a plain notice
 * of exactly what is sent and where. A feature the server doesn't run can't be switched on.
 */
export default function AiFeatures() {
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: AI_FEATURES_KEY, queryFn: fetchAiFeatures });
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const toggle = async (f: AiFeature) => {
    setBusy(f.key);
    setMessage(null);
    setFailure(null);
    try {
      const updated = await setAiFeature(f.key, !f.enabled);
      queryClient.setQueryData<AiFeaturesData>(AI_FEATURES_KEY, (old) =>
        old ? { ...old, features: old.features.map((x) => (x.key === updated.key ? updated : x)) } : old,
      );
      setMessage(`${updated.title} is ${updated.enabled ? "on" : "off"}.`);
    } catch (e) {
      setFailure(errorMessage(e, "Couldn't save your AI setting"));
      queryClient.invalidateQueries({ queryKey: AI_FEATURES_KEY });
    } finally {
      setBusy(null);
    }
  };

  return (
    <section aria-labelledby="ai-heading" className="rounded-lg border border-gray-200 bg-white p-4">
      <h3 id="ai-heading" className="mb-1 font-semibold text-gray-900">AI features</h3>
      <p className="mb-3 text-sm text-gray-600">
        Each one is off until you turn it on. AI answers are checked before they are used, and the built-in rules and your corrections always win.
        {query.data && ` This server's AI spending is capped at $${query.data.monthlyCapUsd} a month.`}
      </p>
      {query.isPending && <p className="text-sm text-gray-600">Checking…</p>}
      {query.error && (
        <div role="alert" className="text-sm text-red-700">
          {errorMessage(query.error)}{" "}
          <button type="button" className="underline" onClick={() => query.refetch()}>Try again</button>
        </div>
      )}
      {query.data && query.data.features.length === 0 && <p className="text-sm text-gray-600">There are no AI features in this version.</p>}
      {query.data && query.data.features.length > 0 && (
        <ul className="space-y-4">
          {query.data.features.map((f) => (
            <li key={f.key} aria-labelledby={`ai-${f.key}-title`} className="border-t border-gray-100 pt-3 first:border-0 first:pt-0">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h4 id={`ai-${f.key}-title`} className="text-sm font-medium text-gray-900">{f.title}</h4>
                  <p className="mt-0.5 text-sm text-gray-600">{f.purpose}</p>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={f.enabled}
                  aria-labelledby={`ai-${f.key}-title`}
                  aria-describedby={`ai-${f.key}-serving`}
                  disabled={busy === f.key || (!f.available && !f.enabled)}
                  onClick={() => toggle(f)}
                  className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors disabled:opacity-50 ${f.enabled ? "bg-blue-600" : "bg-gray-300"}`}
                >
                  <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${f.enabled ? "translate-x-6" : "translate-x-1"}`} />
                </button>
              </div>
              <p id={`ai-${f.key}-serving`} className="mt-2 text-sm text-gray-700">{servingText(f)}</p>
              {evaluationText(f) && <p className="mt-1 text-xs text-gray-600">{evaluationText(f)}</p>}
              <div className="mt-2 grid gap-3 text-sm sm:grid-cols-2">
                <div>
                  <h5 className="text-xs font-semibold uppercase text-gray-600">What is sent</h5>
                  <ul aria-label={`What ${f.title} sends`} className="mt-1 list-disc pl-5 text-gray-700">
                    {f.sends.map((s) => <li key={s}>{s}</li>)}
                  </ul>
                </div>
                <div>
                  <h5 className="text-xs font-semibold uppercase text-gray-600">Never sent</h5>
                  <ul aria-label={`What ${f.title} never sends`} className="mt-1 list-disc pl-5 text-gray-700">
                    {f.neverSends.map((s) => <li key={s}>{s}</li>)}
                  </ul>
                </div>
              </div>
              {f.destination && <p className="mt-2 text-sm text-gray-700"><span className="font-medium">Where it goes:</span> {f.destination}.</p>}
              <p className="mt-1 text-xs text-gray-600">Spent this month: ${f.spentThisMonthUsd}</p>
            </li>
          ))}
        </ul>
      )}
      {message && <p role="status" className="mt-3 text-sm text-gray-700">{message}</p>}
      {failure && <p role="alert" className="mt-3 text-sm text-red-700">{failure}</p>}
    </section>
  );
}
