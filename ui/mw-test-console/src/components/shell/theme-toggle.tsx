"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { THEME_COOKIE, THEMES, nextTheme, type Theme } from "@/lib/theme";

const ICONS = { system: Monitor, light: Sun, dark: Moon } as const;
const LABELS = { system: "System", light: "Light", dark: "Dark" } as const;

export function ThemeToggle({ initial }: { initial: Theme }) {
  const [theme, setTheme] = useState<Theme>(initial);
  const Icon = ICONS[theme];

  function cycle() {
    const next = nextTheme(theme);
    document.cookie = `${THEME_COOKIE}=${next}; path=/; max-age=31536000; samesite=lax; secure`;
    document.documentElement.classList.remove(...THEMES);
    document.documentElement.classList.add(next);
    setTheme(next);
  }

  return (
    <Button variant="ghost" size="icon-sm" onClick={cycle} aria-label={`Theme: ${LABELS[theme]}`} title={`Theme: ${LABELS[theme]} (click to change)`}>
      <Icon />
    </Button>
  );
}
