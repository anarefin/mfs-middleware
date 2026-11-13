// Theme choice (light / dark / system), kept in a cookie so the server renders the right class on
// <html> and no inline script is needed (the CSP allows nonce scripts only). "system" follows
// prefers-color-scheme in CSS (src/app/globals.css).

export const THEMES = ["system", "light", "dark"] as const;
export type Theme = (typeof THEMES)[number];
export const THEME_COOKIE = "mw-admin-theme";
export const SIDEBAR_COOKIE = "mw-admin-sidebar";

export function parseTheme(value: string | undefined): Theme {
  return (THEMES as readonly string[]).includes(value ?? "") ? (value as Theme) : "system";
}

export function nextTheme(t: Theme): Theme {
  return THEMES[(THEMES.indexOf(t) + 1) % THEMES.length]!;
}
