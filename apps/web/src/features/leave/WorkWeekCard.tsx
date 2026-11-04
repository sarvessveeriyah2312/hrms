import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { dateLocale } from '../../i18n';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Card } from '../../components/app/kit';

/** Monday (1) to Sunday (7), named in the current language. 1 Jan 2024 was a Monday. */
const weekDays = () => Array.from({ length: 7 }, (_, i) => {
  const d = new Date(2024, 0, 1 + i);
  return { n: i + 1, short: d.toLocaleDateString(dateLocale(), { weekday: 'short' }), long: d.toLocaleDateString(dateLocale(), { weekday: 'long' }) };
});

/** Which days count as working days for leave, per legal entity. {@code compact}: a strip inside another card. */
export function WorkWeekCard({ compact = false }: { compact?: boolean }) {
  const { t } = useTranslation();
  const weeks = useQuery({ queryKey: ['leave', 'settings', 'work-week'], queryFn: api.leave.settings.workWeeks });
  const body = (
    <>
      {weeks.isError && <p className="form-error" role="alert">{errorText(weeks.error)}</p>}
      {weeks.data?.map((w) => <EntityWeek key={w.entityId} entityId={w.entityId} name={w.name} initial={w.workDays} single={weeks.data.length === 1} compact={compact} />)}
    </>
  );
  if (compact) return <div className="work-week-strip">{body}</div>;
  return (
    <Card title={t('ww.title')} padded>
      <p className="muted">{t('ww.body')}</p>
      {body}
    </Card>
  );
}

function EntityWeek({ entityId, name, initial, single, compact }: { entityId: string; name: string; initial: number[]; single: boolean; compact: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [days, setDays] = useState<number[]>(initial);
  const [saved, setSaved] = useState(false);
  const dirty = days.slice().sort().join() !== initial.slice().sort().join();
  const save = useMutation({
    mutationFn: () => api.leave.settings.setWorkWeek(entityId, days),
    onSuccess: async (data) => {
      queryClient.setQueryData(['leave', 'settings', 'work-week'], data);
      await queryClient.invalidateQueries({ queryKey: ['leave', 'preview'] });
      setSaved(true);
    },
  });
  const toggle = (n: number) => {
    setSaved(false);
    setDays((d) => (d.includes(n) ? d.filter((x) => x !== n) : [...d, n].sort()));
  };

  return (
    <form
      className={compact ? 'work-week is-compact' : 'work-week'}
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      {compact ? <span className="work-week__label">{single ? t('ww.title') : t('ww.titleFor', { name })}</span> : !single && <strong>{name}</strong>}
      <div className="day-toggles" role="group" aria-label={t('ww.daysFor', { name })}>
        {weekDays().map((d) => (
          <label key={d.n} className={days.includes(d.n) ? 'is-on' : undefined} title={d.long}>
            <input type="checkbox" checked={days.includes(d.n)} onChange={() => toggle(d.n)} aria-label={d.long} />
            {d.short}
          </label>
        ))}
      </div>
      {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
      <div className="panel-actions">
        <button type="submit" className={compact ? 'btn small primary' : 'btn primary'} disabled={!dirty || days.length === 0 || save.isPending}>{t('ww.save')}</button>
        {days.length === 0 && <span className="hint">{t('ww.atLeastOne')}</span>}
        {saved && !dirty && <span className="hint" role="status">{t('ww.saved')}</span>}
      </div>
    </form>
  );
}
