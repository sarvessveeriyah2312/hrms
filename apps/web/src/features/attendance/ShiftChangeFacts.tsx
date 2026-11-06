import { useTranslation } from 'react-i18next';
import type { ShiftRequest } from '@hrms/api-client';
import { formatDate, relative } from '../../lib/dates';
import { hhmm } from './rosterText';

const shiftText = (s: { date: string; startTime: string; endTime: string; site: string }) =>
  `${formatDate(s.date, { weekday: 'long', day: 'numeric', month: 'long' })}, ${hhmm(s.startTime)}–${hhmm(s.endTime)} · ${s.site}`;

/** A shift swap, give-away or open-shift claim, for approvers. */
export function ShiftChangeFacts({ request: r }: { request: ShiftRequest }) {
  const { t } = useTranslation();
  return (
    <dl className="facts">
      <dt>{t('schedule.request')}</dt><dd>{t(`schedule.kind.${r.kind}`)}</dd>
      <dt>{t('correction.employee')}</dt><dd>{r.requesterName}</dd>
      {r.shift && <><dt>{r.kind === 'CLAIM' ? t('schedule.openShift') : t('schedule.theirShiftNow')}</dt><dd>{shiftText(r.shift)}</dd></>}
      {r.colleagueName && <><dt>{r.kind === 'GIVE' ? t('schedule.goesTo') : t('schedule.swapWith')}</dt><dd>{r.colleagueName}</dd></>}
      {r.colleagueShift && <><dt>{t('schedule.inReturn')}</dt><dd>{shiftText(r.colleagueShift)}</dd></>}
      {r.reason && <><dt>{t('overtime.reason')}</dt><dd>{r.reason}</dd></>}
      <dt>{t('req.submitted')}</dt><dd>{relative(r.createdAt)}</dd>
    </dl>
  );
}
