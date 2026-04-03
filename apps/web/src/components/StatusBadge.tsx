interface StatusBadgeProps {
  status: string;
  size?: "sm" | "md";
}

const statusColors: Record<string, string> = {
  active: "bg-green-100 text-green-700",
  connected: "bg-green-100 text-green-700",
  paid: "bg-green-100 text-green-700",
  confirmed_safe: "bg-green-100 text-green-700",
  generated: "bg-green-100 text-green-700",
  upcoming: "bg-blue-100 text-blue-700",
  pending: "bg-yellow-100 text-yellow-700",
  due: "bg-amber-100 text-amber-700",
  flagged: "bg-orange-100 text-orange-700",
  warning: "bg-orange-100 text-orange-700",
  open: "bg-red-100 text-red-700",
  overdue: "bg-red-100 text-red-700",
  confirmed_fraud: "bg-red-100 text-red-700",
  error: "bg-red-100 text-red-700",
  cancelled: "bg-gray-100 text-gray-600",
  disconnected: "bg-gray-100 text-gray-600",
  dismissed: "bg-gray-100 text-gray-600",
};

export default function StatusBadge({ status, size = "sm" }: StatusBadgeProps) {
  const colors = statusColors[status] || "bg-gray-100 text-gray-600";
  const sizeClass = size === "sm" ? "text-xs px-2 py-0.5" : "text-sm px-2.5 py-1";

  return (
    <span className={`inline-flex items-center rounded-full font-medium ${colors} ${sizeClass}`}>
      {status.replace(/_/g, " ")}
    </span>
  );
}
