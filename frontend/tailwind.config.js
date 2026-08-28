/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: "class",
  content: ["./src/**/*.{html,ts}"],
  theme: {
    extend: {
      colors: {
        // Semantic tokens backed by CSS custom properties (see styles.scss) so every existing
        // usage of these class names automatically becomes theme-aware without touching templates.
        ink: "var(--color-ink)",
        paper: "var(--color-bg)",
        surface: "var(--color-surface)",
        "surface-alt": "var(--color-surface-alt)",
        muted: "var(--color-muted)",
        line: "var(--color-border)",
        accent: "var(--color-accent)",
        "accent-hover": "var(--color-accent-hover)",
        // Pre-mixed translucent shades. Tailwind's /opacity modifier does not work against the
        // var()-backed tokens above, so translucency needs its own token rather than `bg-paper/85`.
        "paper-translucent": "var(--color-bg-translucent)",
        "surface-soft": "var(--color-surface-alt-soft)",
        "accent-wash": "var(--color-accent-wash)"
      },
      keyframes: {
        "fade-in-up": {
          "0%": { opacity: "0", transform: "translateY(6px)" },
          "100%": { opacity: "1", transform: "translateY(0)" }
        },
        "highlight-pulse": {
          "0%": { boxShadow: "0 0 0 0 rgba(56, 189, 248, 0.45)" },
          "70%": { boxShadow: "0 0 0 8px rgba(56, 189, 248, 0)" },
          "100%": { boxShadow: "0 0 0 0 rgba(56, 189, 248, 0)" }
        },
        "slide-in-right": {
          "0%": { opacity: "0", transform: "translateX(24px)" },
          "100%": { opacity: "1", transform: "translateX(0)" }
        },
        "fade-in": {
          "0%": { opacity: "0" },
          "100%": { opacity: "1" }
        },
        // Retrieval sweep in the landing hero: a band passing down the page while the
        // document is searched, so the wait reads as work rather than a stalled spinner.
        scan: {
          "0%": { transform: "translateY(-30%)", opacity: "0" },
          "25%": { opacity: "1" },
          "75%": { opacity: "1" },
          "100%": { transform: "translateY(320%)", opacity: "0" }
        },
        caret: {
          "0%, 45%": { opacity: "1" },
          "50%, 95%": { opacity: "0" },
          "100%": { opacity: "1" }
        }
      },
      animation: {
        "fade-in-up": "fade-in-up 0.28s ease-out",
        "highlight-pulse": "highlight-pulse 1.2s ease-out 1",
        "slide-in-right": "slide-in-right 0.25s ease-out",
        "fade-in": "fade-in 0.2s ease-out",
        scan: "scan 1.4s ease-in-out infinite",
        caret: "caret 1s step-end infinite"
      }
    }
  },
  plugins: [require("@tailwindcss/typography")]
};
