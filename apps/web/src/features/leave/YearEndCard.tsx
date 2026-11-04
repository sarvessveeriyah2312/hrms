import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { LeaveType } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { relative } from '../../lib/dates';
import { Card } from '../../components/app/kit';

/**
 * Year-end carry-over. Moves each person's unused days into next year, up to each type's limit.
 * Safe to run again; figures HR typed by hand are never changed.
 */
export function YearEndCard({ types }: { types: LeaveType[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [month] = useState(() => new Date().getMonth());
  // In January you're closing last year; otherwise this year.
  const [year, setYear] = useState(month === 0 ? thisYear - 1 : thisYear);
  const runs = useQuery({ queryKey: ['leave', 'settings', 'year-end'], queryFn: api.leave.settings.yearEndRuns });
  const run = useMutation({
    mutationFn: () => api.leave.settings.runYearEnd(year),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['leave'] });
    },
  });
  const carryTypes = types.filter((lt) => lt.active && lt.trackBalance && Number(lt.carryMax) > 0);
  const last = runs.data?.find((r) => r.year === year);

  return (
    <Card title={t('ye.title')} padded>
      <p className="muted">{t('ye.body', { year, next: year + 1 })}</p>
      {carryTypes.length === 0 ? (
        <p className="hint">{t('ye.none')}</p>
      ) : (
        <p className="hint">{t('ye.carries', { list: carryTypes.map((lt) => t('ye.upTo', { name: lt.name, n: Number(lt.carryMax) })).join(', ') })}</p>
      )}
      {run.error && <p className="form-error" role="alert">{errorText(run.error)}</p>}
      {run.data && (
        <p className="notice" role="status">
          {t('ye.done', { days: Number(run.data.days), people: t('common.person', { count: run.data.people }), next: run.data.year + 1 })}
          {run.data.keptManual > 0 && ` ${t('ye.kept', { count: run.data.keptManual })}`}
        </p>
      )}
      <div className="panel-actions">
        <select className="input year-select" value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t('ye.yearToClose')}>
          {[thisYear - 1, thisYear].map((y) => <option key={y} value={y}>{y} → {y + 1}</option>)}
        </select>
        <button type="button" className="btn primary" onClick={() => run.mutate()} disabled={run.isPending || carryTypes.length === 0}>
          {run.isPending ? t('ye.running') : last ? t('ye.again', { year }) : t('ye.run', { year })}
        </button>
        {last && <span className="hint">{t('ye.last', { when: relative(last.runAt), days: Number(last.days), people: t('common.person', { count: last.people }) })}</span>}
      </div>
    </Card>
  );
}
