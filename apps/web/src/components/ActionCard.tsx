"use client";

interface ActionCardProps {
  title: string;
  description: string;
  severity?: "info" | "warning" | "critical";
  actionLabel?: string;
  onAction?: () => void;
  icon?: React.ReactNode;
}

const severityStyles = {
  info: "border-l-blue-400 bg-blue-50",
  warning: "border-l-amber-400 bg-amber-50",
  critical: "border-l-red-400 bg-red-50",
};

export default function ActionCard({
  title,
  description,
  severity = "info",
  actionLabel,
  onAction,
  icon,
}: ActionCardProps) {
  return (
    <div className={`border-l-4 rounded-lg p-4 ${severityStyles[severity]}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3 min-w-0">
          {icon && <span className="text-xl mt-0.5 shrink-0">{icon}</span>}
          <div className="min-w-0">
            <h3 className="font-medium text-gray-900 text-sm">{title}</h3>
            <p className="text-sm text-gray-600 mt-1">{description}</p>
          </div>
        </div>
        {actionLabel && onAction && (
          <button
            onClick={onAction}
            className="shrink-0 text-sm font-medium px-3 py-1.5 rounded-md bg-white border border-gray-200 hover:bg-gray-50 transition-colors"
          >
            {actionLabel}
          </button>
        )}
      </div>
    </div>
  );
}
