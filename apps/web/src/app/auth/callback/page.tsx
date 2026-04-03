"use client";

import { useEffect, useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import type { AuthResponse } from "@/lib/types";

function CallbackContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [status, setStatus] = useState<"loading" | "error">("loading");

  useEffect(() => {
    const code = searchParams.get("code");
    const error = searchParams.get("error");

    if (error || !code) {
      router.replace("/login?error=auth_failed");
      return;
    }

    const exchangeCode = async () => {
      try {
        const res = await api.post<AuthResponse>("/api/v1/auth/google", {
          code,
          redirect_uri: `${window.location.origin}/auth/callback`,
        });

        if (res.success && res.data) {
          const { onboardingStatus } = res.data.user;
          if (onboardingStatus === "completed" || onboardingStatus === "first_sync_done") {
            router.replace("/");
          } else {
            router.replace("/onboarding");
          }
        } else {
          setStatus("error");
          router.replace("/login?error=auth_failed");
        }
      } catch {
        setStatus("error");
        router.replace("/login?error=auth_failed");
      }
    };

    exchangeCode();
  }, [searchParams, router]);

  if (status === "error") {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <p className="text-red-600">Authentication failed. Redirecting...</p>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-blue-600" />
      <p className="mt-4 text-sm text-gray-500">Signing you in...</p>
    </div>
  );
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center">Loading...</div>}>
      <CallbackContent />
    </Suspense>
  );
}
