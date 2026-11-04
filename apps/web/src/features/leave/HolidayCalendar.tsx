import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { Holiday } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate, isoDate, monthGrid, parseIso, weekdayNames } from '../../lib/dates';
import { Card } from '../../components/app/kit';
import { Icon } from '../../components/Icon';
import { WorkWeekCard } from './WorkWeekCard';


const long = (iso: string) => formatDate(iso, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
const short = (iso: string) => formatDate(iso, { weekday: 'short', day: 'numeric', month: 'short' });

/**
 * Public holidays, one month at a time. Pick a day to add a holiday there, or a marked day to see its name
 * and remove it. Non-working days (from the working week) are dimmed. Covers last year to next year.
 */
export function HolidayCalendar({ holidays }: { holidays: Holiday[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [now] = useState(() => new Date());
  const [today] = useState(() => isoDate(now));
  const [cursor, setCursor] = useState(() => ({ year: now.getFullYear(), month: now.getMonth() }));
  const [selected, setSelected] = useState<string | null>(null);
  const [name, setName] = useState('');
  const weeks = useQuery({ queryKey: ['leave', 'settings', 'work-week'], queryFn: api.leave.settings.workWeeks });
  const workDays = new Set(weeks.data?.[0]?.workDays ?? [1, 2, 3, 4, 5]);

  const byDate = useMemo(() => new Map(holidays.map((h) => [h.date, h])), [holidays]);
  const monthKey = `${cursor.year}-${String(cursor.month + 1).padStart(2, '0')}`;
  const inMonth = holidays.filter((h) => h.date.startsWith(monthKey));
  const picked = selected ? byDate.get(selected) : undefined;
  const title = formatDate(new Date(cursor.year, cursor.month, 1), { month: 'long', year: 'numeric' });

  // The API keeps last year to next year; don't page beyond that.
  const index = cursor.year * 12 + cursor.month;
  const minIndex = (now.getFullYear() - 1) * 12;
  const maxIndex = (now.getFullYear() + 1) * 12 + 11;
  const go = (delta: number) => {
    const next = Math.min(maxIndex, Math.max(minIndex, index + delta));
    setCursor({ year: Math.floor(next / 12), month: next % 12 });
    setSelected(null);
  };

  const done = async () => {
    setName('');
    await queryClient.invalidateQueries({ queryKey: ['leave'] });
  };
  const add = useMutation({ mutationFn: () => api.leave.settings.addHoliday(selected!, name.trim()), onSuccess: done });
  const remove = useMutation({
    mutationFn: (id: string) => api.leave.settings.deleteHoliday(id),
    onSuccess: async () => {
      await done();
      setSelected(null);
    },
  });
  const error = add.error ?? remove.error;

  const pick = (iso: string) => {
    setSelected(iso === selected ? null : iso);
    setName('');
    add.reset();
    remove.reset();
  };

  return (
    <Card
      title={t('cal.title')}
      action={
        <div className="cal-nav">
          <button type="button" className="icon-pill" onClick={() => go(-1)} disabled={index <= minIndex} aria-label={t('cal.prev')}>
            <Icon name="chevron" size={16} style={{ transform: 'rotate(180deg)' }} />
          </button>
          <button type="button" className="cal-nav__title" onClick={() => setCursor({ year: now.getFullYear(), month: now.getMonth() })}
            title={t('cal.thisMonth')} aria-live="polite">
            {title}
          </button>
          <button type="button" className="icon-pill" onClick={() => go(1)} disabled={index >= maxIndex} aria-label={t('cal.next')}>
            <Icon name="chevron" size={16} />
          </button>
        </div>
      }
    >
      <div className="cal-month cal-month--single" aria-label={title}>
        <div className="cal-days">
          {weekdayNames().map((d) => <span key={d} className="cal-wd" aria-hidden="true">{d}</span>)}
          {monthGrid(cursor.year, cursor.month).map((iso, i) => {
            if (!iso) return <span key={`p${i}`} aria-hidden="true" />;
            const h = byDate.get(iso);
            const dow = ((parseIso(iso).getDay() + 6) % 7) + 1;
            const cls = ['cal-day', h && 'is-holiday', !workDays.has(dow) && 'is-off', iso === today && 'is-today', iso === selected && 'is-selected']
              .filter(Boolean).join(' ');
            return (
              <button key={iso} type="button" className={cls} onClick={() => pick(iso)} title={h?.name}
                aria-label={`${long(iso)}${h ? t('cal.dayLabelHoliday', { name: h.name }) : ''}${!workDays.has(dow) ? t('cal.dayLabelOff') : ''}`}
                aria-pressed={iso === selected}>
                {Number(iso.slice(8))}
              </button>
            );
          })}
        </div>
      </div>

      <div className="cal-bar" aria-live="polite">
        {!selected ? (
          <p className="hint">{t('hol.pickHint')}</p>
        ) : picked ? (
          <div className="cal-bar__row">
            <span className="cal-bar__date">{long(selected)}</span>
            <strong>{picked.name}</strong>
            <span className="panel-actions">
              <button type="button" className="btn small reject" onClick={() => remove.mutate(picked.id)} disabled={remove.isPending}>{t('common.remove')}</button>
              <button type="button" className="link-button" onClick={() => setSelected(null)}>{t('common.cancel')}</button>
            </span>
          </div>
        ) : (
          <form
            className="cal-bar__row"
            onSubmit={(e) => {
              e.preventDefault();
              if (name.trim()) add.mutate();
            }}
          >
            <span className="cal-bar__date">{long(selected)}</span>
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder={t('hol.name')}
              aria-label={t('hol.name')} autoFocus maxLength={80} required />
            <span className="panel-actions">
              <button type="submit" className="btn small primary" disabled={add.isPending || !name.trim()}>{t('hol.add')}</button>
              <button type="button" className="link-button" onClick={() => setSelected(null)}>{t('common.cancel')}</button>
            </span>
          </form>
        )}
        {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      </div>

      <ul className="cal-list" aria-label={t('hol.inYear', { year: title })}>
        {inMonth.map((h) => (
          <li key={h.id}>
            <button type="button" className="cal-list__item" onClick={() => pick(h.date)}>
              <span className="cal-dot" aria-hidden="true" />
              <span className="cal-list__name">{h.name}</span>
              <span className="hint">{short(h.date)}</span>
            </button>
          </li>
        ))}
        {inMonth.length === 0 && <li className="hint cal-list__empty">{t('hol.noneThisMonth')}</li>}
      </ul>

      <WorkWeekCard compact />
    </Card>
  );
}
