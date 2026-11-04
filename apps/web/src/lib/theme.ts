import { useSyncExternalStore } from 'react';
import { toCssVariables } from '@hrms/ui-tokens';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'hrms-theme';
const listeners = new Set<() => void>();

function readStored(): Theme {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light'; // storage blocked (private window, etc.): fall back to the default
  }
}

let current: Theme = readStored();

/** Writes the shared design tokens as CSS variables and marks <html data-theme> for CSS that needs it. */
function apply(theme: Theme) {
  const root = document.documentElement;
  for (const [name, value] of Object.entries(toCssVariables(theme))) {
    root.style.setProperty(name, value);
  }
  root.dataset.theme = theme;
  root.style.colorScheme = theme; // native controls and scrollbars follow too
}

/** Call once at startup, before the first render. Light is the default until the user picks dark. */
export function applyTheme() {
  apply(current);
}

export function setTheme(theme: Theme) {
  current = theme;
  apply(theme);
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // not saved; the choice still holds for this visit
  }
  listeners.forEach((l) => l());
}

export function toggleTheme() {
  setTheme(current === 'dark' ? 'light' : 'dark');
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, () => current, () => 'light');
}
