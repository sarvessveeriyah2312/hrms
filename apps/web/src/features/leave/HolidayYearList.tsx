import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { Holiday } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate } from '../../lib/dates';
import { Card } from '../../components/app/kit';
import { Icon } from '../../components/Icon';

/** Every holiday in a year, grouped by month, with remove. Sits beside the month calendar. */
export function HolidayYearList({ holidays }: { holidays: Holiday[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [year, setYear] = useState(thisYear);
  const remove = useMutation({
    mutationFn: (id: string) => api.leave.settings.deleteHoliday(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['leave'] }),
  });
  const inYear = holidays.filter((h) => h.date.startsWith(String(year))).sort((a, b) => a.date.localeCompare(b.date));
  const months = [...new Set(inYear.map((h) => h.date.slice(0, 7)))];

  return (
    <Card
      title={t('hol.inYear', { year })}
      count={inYear.length}
      action={
        <div className="cal-nav">
          <button type="button" className="icon-pill" onClick={() => setYear((y) => y - 1)} disabled={year <= thisYear - 1} aria-label={t('hol.prevYear')}>
            <Icon name="chevron" size={16} style={{ transform: 'rotate(180deg)' }} />
          </button>
          <button type="button" className="icon-pill" onClick={() => setYear((y) => y + 1)} disabled={year >= thisYear + 1} aria-label={t('hol.nextYear')}>
            <Icon name="chevron" size={16} />
          </button>
        </div>
      }
    >
      {remove.error && <p className="form-error card-pad" role="alert">{errorText(remove.error)}</p>}
      {inYear.length === 0 ? (
        <p className="hint card-pad">{t('hol.noneInYear', { year })}</p>
      ) : (
        <div className="hol-list">
          {months.map((m) => (
            <section key={m} className="hol-month">
              <h3>{formatDate(`${m}-01`, { month: 'long' })}</h3>
              <ul>
                {inYear.filter((h) => h.date.startsWith(m)).map((h) => (
                  <li key={h.id}>
                    <span className="hol-date">
                      <strong>{Number(h.date.slice(8))}</strong>
                      <small>{formatDate(h.date, { weekday: 'short' })}</small>
                    </span>
                    <span className="hol-name">{h.name}</span>
                    <button type="button" className="btn small" onClick={() => remove.mutate(h.id)} disabled={remove.isPending}
                      aria-label={t('hol.removeName', { name: h.name })}>{t('common.remove')}</button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </Card>
  );
}
