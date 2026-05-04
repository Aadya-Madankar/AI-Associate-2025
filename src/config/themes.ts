// Theme configuration with CSS variables
export interface ThemeConfig {
    id: string;
    name: string;
    cssVariables: Record<string, string>;
}

export const DARK_THEME: ThemeConfig = {
    id: 'dark',
    name: 'Dark Professional',
    cssVariables: {
        '--bg-primary': '#0f172a',
        '--bg-secondary': '#1e293b',
        '--bg-card': 'rgba(30, 41, 59, 0.8)',
        '--text-primary': '#f1f5f9',
        '--text-secondary': '#94a3b8',
        '--border': 'rgba(100, 116, 139, 0.3)',
        '--accent': 'var(--agent-primary)',
        '--accent-glow': 'var(--agent-primary)',
    },
};

export const LIGHT_THEME: ThemeConfig = {
    id: 'light',
    name: 'Light Clean',
    cssVariables: {
        '--bg-primary': '#f8fafc',
        '--bg-secondary': '#e2e8f0',
        '--bg-card': 'rgba(255, 255, 255, 0.9)',
        '--text-primary': '#1e293b',
        '--text-secondary': '#64748b',
        '--border': 'rgba(148, 163, 184, 0.3)',
        '--accent': 'var(--agent-primary)',
        '--accent-glow': 'var(--agent-primary)',
    },
};

export const NEON_THEME: ThemeConfig = {
    id: 'neon',
    name: 'Neon Vibrant',
    cssVariables: {
        '--bg-primary': '#09090b',
        '--bg-secondary': '#18181b',
        '--bg-card': 'rgba(24, 24, 27, 0.9)',
        '--text-primary': '#fafafa',
        '--text-secondary': '#a1a1aa',
        '--border': 'rgba(168, 85, 247, 0.4)',
        '--accent': 'var(--agent-primary)',
        '--accent-glow': 'var(--agent-primary)',
    },
};

export const THEMES: Record<string, ThemeConfig> = {
    dark: DARK_THEME,
    light: LIGHT_THEME,
    neon: NEON_THEME,
};

// Apply theme to document
export function applyTheme(themeId: string, primaryColor: string, accentColor: string) {
    const theme = THEMES[themeId] || DARK_THEME;
    const root = document.documentElement;

    // Set agent colors first
    root.style.setProperty('--agent-primary', primaryColor);
    root.style.setProperty('--agent-accent', accentColor);

    // Apply theme variables
    Object.entries(theme.cssVariables).forEach(([key, value]) => {
        root.style.setProperty(key, value);
    });

    // Add theme class for conditional styles
    root.className = `theme-${themeId}`;
}
