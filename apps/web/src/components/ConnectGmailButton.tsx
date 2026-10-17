"use client";

import { useMutation } from "@tanstack/react-query";
import { connectProblem, startGmailConnect } from "@/lib/gmail";

/**
 * Starts Connect Gmail (AOS-406): asks the API for Google's consent URL (with a fresh state) and
 * leaves for Google. If that fails (e.g. Google sign-in isn't configured) it says why, in place.
 */
export default function ConnectGmailButton({ label = "Connect Gmail", className = "" }: { label?: string; className?: string }) {
  const start = useMutation({ mutationFn: startGmailConnect });
  const problem = start.error ? connectProblem(start.error) : null;

  return (
    <div className="space-y-2">
      <button
        type="button"
        onClick={() => start.mutate()}
        // Stays disabled after success: the browser is already on its way to Google.
        disabled={start.isPending || start.isSuccess}
        className={`text-xs px-2.5 py-1.5 rounded-md border border-blue-200 text-blue-600 hover:bg-blue-50 disabled:opacity-60 ${className}`}
      >
        {start.isPending || start.isSuccess ? "Opening Google…" : label}
      </button>
      {problem && (
        <p role="alert" className="text-xs text-red-700">
          <strong>{problem.title}.</strong> {problem.detail}
        </p>
      )}
    </div>
  );
}
