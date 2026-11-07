import { useQuery } from '@tanstack/react-query';
import type { PayslipTemplate } from '@hrms/api-client';
import i18n from '../../i18n';
import { api } from '../../lib/api';

/** The layouts to choose from and the company's default. */
export function usePayslipTemplates() {
  return useQuery({ queryKey: ['payroll', 'templates'], queryFn: () => api.payroll.templates(), staleTime: 60_000 });
}

/** A layout's name and description in the current language (the API's English when there's no translation). */
export function templateText(t: PayslipTemplate): { name: string; description: string } {
  const key = `payroll.templateNames.${t.code}`;
  const tr = (k: string, fallback: string) => (i18n.exists(k) ? (i18n.t as (x: string) => string)(k) : fallback);
  return { name: tr(`${key}.name`, t.name), description: tr(`${key}.description`, t.description) };
}
