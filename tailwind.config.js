/** @type {import('tailwindcss').Config} */
export default {
  darkMode: "class",
  content: [
    './pages/**/*.{ts,tsx}',
    './components/**/*.{ts,tsx}',
    './app/**/*.{ts,tsx}',
    './src/**/*.{ts,tsx}',
    './index.html',
  ],
  theme: {
    extend: {
      // Beautiful green gradient backgrounds
      backgroundImage: {
        'gradient-light': 'linear-gradient(135deg, #1f4037, #99f2c8)',
        'gradient-dark': 'linear-gradient(135deg, #0f172a, #1e293b, #0f172a)',
        'sidebar-light': 'linear-gradient(180deg, #2d5016, #a7f3d0, #ecfdf5)',
        'sidebar-dark': 'linear-gradient(180deg, #1e293b, #0f172a)',
        'status-light': 'linear-gradient(90deg, #065f46, #6ee7b7, #d1fae5)',
        'status-dark': 'linear-gradient(90deg, #374151, #1f2937)',
        'conversation-light': 'linear-gradient(180deg, #047857, #86efac, #f0fdf4)',
      },
      colors: {
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
      },
    },
  },
  plugins: [],
}
