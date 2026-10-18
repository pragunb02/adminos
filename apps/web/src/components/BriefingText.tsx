"use client";

import { useState } from "react";
import type { Briefing } from "@/lib/types";

/**
 * A briefing's words (AOS-716, D-140). `content` is always the app's own template with the plain numbers. When the
 * server kept an AI wording (it passed the number and name check), that is shown instead, marked "Worded by AI",
 * with a button to see the plain numbers. When the AI wording was thrown away or unavailable, the template is shown
 * with a notice saying so. All text renders as text, never as HTML.
 */
export default function BriefingText({ briefing }: { briefing: Briefing }) {
  const [plain, setPlain] = useState(false);
  const narrated = briefing.narrationStatus === "narrated" && briefing.narration != null;
  const text = narrated && !plain ? briefing.narration : briefing.content;

  return (
    <div className="mb-4 space-y-2">
      {narrated && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="rounded-full bg-violet-100 px-2 py-0.5 font-medium text-violet-800" title={briefing.narrationModel ?? undefined}>
            {plain ? "Plain numbers" : "Worded by AI"}
          </span>
          <button
            type="button"
            aria-pressed={plain}
            onClick={() => setPlain((p) => !p)}
            className="rounded-md border border-gray-200 bg-white px-2 py-0.5 font-medium text-gray-700 hover:bg-gray-50"
          >
            Show the plain numbers
          </button>
        </div>
      )}
      <p className="prose prose-sm max-w-none whitespace-pre-line text-gray-700" data-testid="briefing-text">
        {text}
      </p>
      {narrated && !plain && (
        <p className="text-xs text-gray-500">Every number and name in this wording was checked against your briefing.</p>
      )}
      {briefing.narrationStatus === "rejected" && (
        <p role="note" className="text-xs text-amber-800">
          The AI wording didn&apos;t match your numbers, so it wasn&apos;t used. This is the plain briefing.
        </p>
      )}
      {briefing.narrationStatus === "unavailable" && (
        <p role="note" className="text-xs text-gray-500">The AI wording wasn&apos;t available this week. This is the plain briefing.</p>
      )}
    </div>
  );
}
