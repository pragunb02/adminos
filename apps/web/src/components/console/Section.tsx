import type { ReactNode } from "react";

/** A titled card on the console (calm: white, 12px radius, no shadow). Shows its own error with retry. */
export default function Section({
  title, action, error, onRetry, children,
}: { title: string; action?: ReactNode; error?: string | null; onRetry?: () => void; children: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-xl border border-line bg-surface p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-ink">{title}</h3>
        {action}
      </div>
      {error ? (
        <div role="alert" className="text-sm text-negative">
          {error}{" "}
          {onRetry && (
            <button type="button" onClick={onRetry} className="font-medium underline">
              Try again
            </button>
          )}
        </div>
      ) : (
        children
      )}
    </section>
  );
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div aria-hidden className="space-y-2">
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="h-4 animate-pulse rounded bg-line" style={{ width: `${90 - i * 15}%` }} />
      ))}
    </div>
  );
}
