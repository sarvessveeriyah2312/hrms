import type { TFunction } from 'i18next';
import type { OvertimeRule } from '@hrms/api-client';

/** 2.5, 4, 0.25 in the person's number format. */
export const num = (n: number) => Number(n).toLocaleString(undefined, { maximumFractionDigits: 2 });

/** 1.5×, 2.0×, 1.75× */
export const rateText = (rate: number) => {
  const v = Number(rate);
  return `${v.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 2 })}×`;
};

/** The rule's rates an employee can pick from, lowest first. */
export const ruleRates = (r: OvertimeRule) => [...new Set([r.rateWorking, r.rateRest, r.rateHoliday].map(Number))].sort((a, b) => a - b);

/** The rule's rate for a kind of day. */
export const rateForDay = (r: OvertimeRule, kind: 'WORKING' | 'REST' | 'HOLIDAY') =>
  Number(kind === 'HOLIDAY' ? r.rateHoliday : kind === 'REST' ? r.rateRest : r.rateWorking);

/** "Half a day for every 4 hours, hours add up across requests; expires 90 days after it's earned." */
export function ruleText(t: TFunction, r: OvertimeRule) {
  const rates = t('otRules.summary.rates', { working: rateText(r.rateWorking), rest: rateText(r.rateRest), holiday: rateText(r.rateHoliday) })
    + (r.rateMode === 'EMPLOYEE' ? ` ${t('otRules.summary.employeePicks')}` : '');
  const pre = r.requirePreApproval ? ` · ${t('otRules.summary.preApproval')}` : '';
  if (r.compensation === 'PAY' || !r.leaveTypeId) return `${t('otRules.summary.pay')} · ${rates}${pre}`;
  const unit = t(r.creditUnit === 'FULL_DAY' ? 'otRules.summary.fullDay' : 'otRules.summary.halfDay', { hours: num(r.hoursPerUnit), type: r.leaveTypeName });
  const parts = [
    r.compensation === 'CHOICE' ? t('otRules.summary.choice') : t('otRules.summary.timeOff'),
    unit,
    t(r.conversion === 'BANKED' ? 'otRules.summary.banked' : 'otRules.summary.perRequest'),
    r.applyRate ? t('otRules.summary.rateApplies') : null,
    r.compensation === 'CHOICE' || r.applyRate ? rates : null,
    r.expiry === 'DAYS' ? t('otRules.summary.expiresDays', { n: r.expiryDays }) : r.expiry === 'YEAR_END' ? t('otRules.summary.expiresYearEnd') : null,
  ];
  return parts.filter(Boolean).join(' · ') + pre;
}
