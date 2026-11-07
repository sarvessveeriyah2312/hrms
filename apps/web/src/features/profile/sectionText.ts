import type { ProfileField, ProfileSectionSpec, SectionData } from '@hrms/api-client';
import i18n from '../../i18n';
import { formatDate } from '../../lib/dates';

/** A translation by a key built at run time (section, field and option names come from the API's spec). */
export function tx(key: string, fallback?: string, options?: Record<string, unknown>): string {
  return i18n.exists(key) ? (i18n.t as (k: string, o?: Record<string, unknown>) => string)(key, options) : (fallback ?? key);
}

/** "accountNumber" → "Account number", used when a label has no translation. */
function humanize(key: string) {
  const words = key.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export const sectionLabel = (key: string) => tx(`profile.section.${key}`, humanize(key));
export const sectionHint = (key: string) => tx(`profile.sectionHint.${key}`, '');
export const fieldLabel = (key: string) => tx(`profile.field.${key}`, humanize(key));
export const optionLabel = (value: string) => tx(`profile.option.${value}`, humanize(value));

/** How a stored value reads: dates in the current language, options by name. */
export function formatValue(field: ProfileField, value: unknown): string {
  if (value === null || value === undefined || value === '') return '';
  const v = String(value);
  if (field.type === 'DATE') return formatDate(v, { day: 'numeric', month: 'short', year: 'numeric' });
  if (field.type === 'SELECT') return optionLabel(v);
  return v;
}

export function items(data: SectionData | undefined): Record<string, unknown>[] {
  const raw = data?.items;
  return Array.isArray(raw) ? (raw as Record<string, unknown>[]) : [];
}

export function isEmpty(spec: ProfileSectionSpec, data: SectionData | undefined): boolean {
  return spec.list ? items(data).length === 0 : !data || Object.keys(data).length === 0;
}

/** One list entry as a line, e.g. "Ali bin Abu · Brother · +60 12-345 6789". */
export function itemSummary(spec: ProfileSectionSpec, item: Record<string, unknown>): { title: string; meta: string } {
  const values = spec.fields.map((f) => formatValue(f, item[f.key])).filter(Boolean);
  return { title: values[0] ?? '', meta: values.slice(1).join(' · ') };
}
