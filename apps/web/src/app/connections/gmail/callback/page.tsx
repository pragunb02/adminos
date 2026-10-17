"use client";

import { Suspense, useEffect, useRef } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import ConnectGmailButton from "@/components/ConnectGmailButton";
import {
  GMAIL_CALLBACK_PATH,
  connectProblem,
  finishGmailConnect,
  googleProblem,
  incompleteProblem,
  type GmailProblem,
} from "@/lib/gmail";

/**
 * Where Google sends the user back after the Gmail consent (AOS-406, D-64). The route guard keeps
 * it signed-in only. Google's code and state are posted once; the API checks the state first.
 * On success the address is replaced with `?connected=1`, so a refresh or Back never re-posts a
 * used code.
 */
function Callback() {
  const search = useSearchParams();
  const router = useRouter();
  const queryClient = useQueryClient();
  const connected = search.get("connected") === "1";
  const code = search.get("code");
  const state = search.get("state");
  const googleError = search.get("error");

  const finish = useMutation({
    mutationFn: ({ code, state }: { code: string; state: string }) => finishGmailConnect(code, state),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["settings"] });
      router.replace(`${GMAIL_CALLBACK_PATH}?connected=1`);
    },
  });

  // One redemption per page load, even if React runs the effect twice (development, Strict Mode).
  const posted = useRef(false);
  useEffect(() => {
    if (connected || googleError || !code || !state || posted.current) return;
    posted.current = true;
    finish.mutate({ code, state });
  }, [connected, googleError, code, state, finish]);

  if (connected) return <Connected />;
  const problem: GmailProblem | null = googleError
    ? googleProblem(googleError)
    : !code || !state
      ? incompleteProblem
      : finish.error
        ? connectProblem(finish.error)
        : null;
  if (problem) return <Problem problem={problem} />;
  return (
    <div role="status" className="flex items-center gap-3 text-sm text-ink-muted">
      <span aria-hidden className="h-5 w-5 animate-spin rounded-full border-2 border-line border-t-accent" />
      Connecting Gmail…
    </div>
  );
}

function Connected() {
  return (
    <div role="status" className="space-y-3 rounded-lg border border-line bg-surface p-4">
      <p className="font-semibold">Gmail connected — first sync started</p>
      <p className="text-sm text-ink-muted">Bank alerts and receipts from the last 30 days are being read now; new ones arrive on their own.</p>
      <div className="flex gap-4 text-sm">
        <Link href="/settings" className="font-medium text-accent underline">Back to Settings</Link>
        <Link href="/" className="font-medium text-accent underline">Go to Home</Link>
      </div>
    </div>
  );
}

function Problem({ problem }: { problem: GmailProblem }) {
  return (
    <div role="alert" className="space-y-3 rounded-lg border border-red-200 bg-red-50 p-4">
      <p className="font-semibold text-red-800">{problem.title}</p>
      <p className="text-sm text-red-700">{problem.detail}</p>
      <div className="flex items-start gap-4 text-sm">
        {problem.canRetry && <ConnectGmailButton label="Start again" />}
        <Link href="/settings" className="font-medium text-accent underline">Back to Settings</Link>
      </div>
    </div>
  );
}

export default function GmailCallbackPage() {
  return (
    <AppShell>
      <div className="space-y-4">
        <h2 className="text-xl font-bold">Connect Gmail</h2>
        <Suspense fallback={<p className="text-sm text-ink-muted">Loading…</p>}>
          <Callback />
        </Suspense>
      </div>
    </AppShell>
  );
}
