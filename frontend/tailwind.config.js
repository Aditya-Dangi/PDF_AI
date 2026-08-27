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
        "accent-hover": "var(--color-accent-hover)"
      },
      keyframes: {
        "fade-in-up": {
          "0%": { opacity: "0", transform: "translateY(6px)" },
          "100%": { opacity: "1", transform: "translateY(0)" }
        },
        "highlight-pulse": {
          "0%": { boxShadow: "0 0 0 0 rgba(234, 179, 8, 0.5)" },
          "70%": { boxShadow: "0 0 0 8px rgba(234, 179, 8, 0)" },
          "100%": { boxShadow: "0 0 0 0 rgba(234, 179, 8, 0)" }
        },
        "slide-in-right": {
          "0%": { opacity: "0", transform: "translateX(24px)" },
          "100%": { opacity: "1", transform: "translateX(0)" }
        },
        "fade-in": {
          "0%": { opacity: "0" },
          "100%": { opacity: "1" }
        }
      },
      animation: {
        "fade-in-up": "fade-in-up 0.28s ease-out",
        "highlight-pulse": "highlight-pulse 1.2s ease-out 1",
        "slide-in-right": "slide-in-right 0.25s ease-out",
        "fade-in": "fade-in 0.2s ease-out"
      }
    }
  },
  plugins: [require("@tailwindcss/typography")]
};
