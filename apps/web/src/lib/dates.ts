import type { DayPart } from '@hrms/api-client';
import type { Tone } from '../components/Glyph';
import i18n, { dateLocale } from '../i18n';

const TONES: Tone[] = ['blue', 'green', 'orange', 'teal', 'purple', 'pink'];

export function toTone(value: string | null | undefined, fallback: Tone = 'teal'): Tone {
  return TONES.includes(value as Tone) ? (value as Tone) : fallback;
}

/** yyyy-mm-dd in local time (not UTC, which can be a different day). */
export function isoDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function addDays(iso: string, days: number): string {
  const d = parseIso(iso);
  d.setDate(d.getDate() + days);
  return isoDate(d);
}

export function parseIso(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

const dayMonth = (d: Date) => d.toLocaleDateString(dateLocale(), { day: 'numeric', month: 'short' });

/** A date in the current language, e.g. formatDate(iso, { weekday: 'long', day: 'numeric', month: 'long' }). */
export function formatDate(value: string | Date, options: Intl.DateTimeFormatOptions): string {
  const d = typeof value === 'string' ? (value.length === 10 ? parseIso(value) : new Date(value)) : value;
  return d.toLocaleDateString(dateLocale(), options);
}

/** The month name in the current language (1 = January). */
export function monthName(month: number, style: 'long' | 'short' = 'long'): string {
  return new Date(2000, month - 1, 1).toLocaleDateString(dateLocale(), { month: style });
}

/** Monday-first weekday names in the current language. */
export function weekdayNames(style: 'short' | 'narrow' = 'short'): string[] {
  return Array.from({ length: 7 }, (_, i) => new Date(2024, 0, 1 + i).toLocaleDateString(dateLocale(), { weekday: style }));
}

/** "12 Nov", "12–14 Nov", "28 Nov – 2 Dec", "12 Nov (morning)". */
export function formatRange(start: string, end: string, part: DayPart = 'FULL'): string {
  const s = parseIso(start);
  const e = parseIso(end);
  if (start === end) {
    if (part === 'FULL') return dayMonth(s);
    return i18n.t('dates.withPart', { date: dayMonth(s), part: i18n.t(part === 'AM' ? 'dates.morning' : 'dates.afternoon') });
  }
  if (s.getMonth() === e.getMonth() && s.getFullYear() === e.getFullYear() && i18n.language === 'en') return `${s.getDate()}–${dayMonth(e)}`;
  return `${dayMonth(s)} – ${dayMonth(e)}`;
}

export function formatDays(days: number): string {
  const n = Number(days);
  return i18n.t('common.day', { count: n });
}

/** "Today 08:14", "Yesterday", "3 Nov". */
export function relative(iso: string): string {
  const d = new Date(iso);
  const today = new Date();
  const time = d.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' });
  if (isoDate(d) === isoDate(today)) return i18n.t('dates.today', { time });
  const y = new Date(today);
  y.setDate(y.getDate() - 1);
  if (isoDate(d) === isoDate(y)) return i18n.t('dates.yesterday', { time });
  return dayMonth(d);
}

/** Days of a month as a Monday-first grid; null cells pad the first week. */
export function monthGrid(year: number, month: number): (string | null)[] {
  const pad = (new Date(year, month, 1).getDay() + 6) % 7; // Monday = 0
  const days = new Date(year, month + 1, 0).getDate();
  const cells: (string | null)[] = Array.from({ length: pad }, () => null);
  for (let d = 1; d <= days; d++) cells.push(isoDate(new Date(year, month, d)));
  return cells;
}

/** ISO day of week: Monday = 1 … Sunday = 7. */
export function isoWeekday(iso: string): number {
  return ((parseIso(iso).getDay() + 6) % 7) + 1;
}
