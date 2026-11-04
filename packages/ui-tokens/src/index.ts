/**
 * Design tokens (PRD section 11). One source for web and mobile.
 * Status is always shown as icon + label + colour, never colour alone.
 * `*Text` colours are the darker shades that pass 4.5:1 contrast on white.
 */
export const colors = {
  light: {
    primary: '#2563EB',
    primaryHover: '#1D4ED8',
    primarySoft: '#EFF6FF',
    success: '#16A34A',
    successText: '#15803D',
    successSoft: '#DCFCE7',
    warning: '#D97706',
    warningText: '#B45309',
    warningSoft: '#FEF3C7',
    danger: '#DC2626',
    dangerText: '#B91C1C',
    dangerSoft: '#FEE2E2',
    text: '#0F172A',
    textSecondary: '#475569',
    textMuted: '#64748B',
    border: '#E2E8F0',
    borderStrong: '#CBD5E1',
    surface: '#FFFFFF',
    background: '#F8FAFC',
  },
  dark: {
    primary: '#60A5FA',
    primaryHover: '#93C5FD',
    primarySoft: '#172554',
    success: '#4ADE80',
    successText: '#4ADE80',
    successSoft: '#052E16',
    warning: '#FBBF24',
    warningText: '#FBBF24',
    warningSoft: '#451A03',
    danger: '#F87171',
    dangerText: '#F87171',
    dangerSoft: '#450A0A',
    text: '#F8FAFC',
    textSecondary: '#CBD5E1',
    textMuted: '#94A3B8',
    border: '#1E293B',
    borderStrong: '#334155',
    surface: '#0F172A',
    background: '#020617',
  },
} as const;

export type ColorScheme = keyof typeof colors;
export type ColorToken = keyof typeof colors.light;

export const fontSize = { caption: 12, small: 14, body: 16, subtitle: 20, title: 24, display: 32 } as const;
export const fontWeight = { regular: '400', medium: '500', semibold: '600', bold: '700' } as const;
export const space = { 1: 4, 2: 8, 3: 12, 4: 16, 5: 24, 6: 32, 7: 48 } as const;
export const radius = { input: 6, button: 12, card: 12, sheet: 24, pill: 999 } as const;
export const touchTarget = 48;

/** CSS custom properties for the web app, e.g. `--color-primary`. */
export function toCssVariables(scheme: ColorScheme): Record<string, string> {
  const vars: Record<string, string> = {};
  for (const [name, value] of Object.entries(colors[scheme])) {
    vars[`--color-${name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`] = value;
  }
  return vars;
}

export type RequestStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'CANCELLED';

/** Maps a status to its colour role; UIs pair it with an icon and a label. */
export const statusTone: Record<RequestStatus, 'neutral' | 'warning' | 'success' | 'danger'> = {
  DRAFT: 'neutral',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  RETURNED: 'warning',
  CANCELLED: 'neutral',
};
