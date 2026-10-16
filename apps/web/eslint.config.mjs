import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const config = [
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      // Data loading goes through TanStack Query (AOS-308, D-45); setState in an effect body is a bug.
      "react-hooks/set-state-in-effect": "error",
    },
  },
  { ignores: [".next/**", "node_modules/**", "next-env.d.ts", "coverage/**", "playwright-report/**", "test-results/**"] },
];

export default config;
