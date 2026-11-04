import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { DayPart, FileMeta, LeaveBalance, LeaveRequest } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDays, formatRange, monthGrid, toTone, weekdayNames } from '../../lib/dates';
import { Card, EmptyState, List } from '../../components/app/kit';
import { Glyph } from '../../components/Glyph';
import { FileField } from '../../components/app/files';
import { Chip, MonthNav } from './LeaveCalendarTab';
import { longDate, useLeaveMonth, useMonth } from './leaveMonth';

const n = (v: number | null | undefined) => {
  const x = Number(v ?? 0);
  return Number.isInteger(x) ? String(x) : x.toFixed(1);
};

/**
 * Plan leave without sending it: pick the days on the calendar, save them as a plan, and see what's left
 * afterwards. Plans aren't sent to anyone and don't use the balance until they're submitted.
 */
export function LeavePlannerTab({ balances, startOn }: { balances: LeaveBalance[]; startOn: string | null }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const month = useMonth();
  const data = useLeaveMonth(month, { team: false });
  const types = useQuery({ queryKey: ['leave', 'types'], queryFn: api.leave.types });
  const [editing, setEditing] = useState<LeaveRequest | null>(null);
  const [start, setStart] = useState<string | null>(startOn);
  const [end, setEnd] = useState<string | null>(startOn);
  const [awaitingEnd, setAwaitingEnd] = useState(!!startOn);
  const [hover, setHover] = useState<string | null>(null);
  const [typeId, setTypeId] = useState('');
  const [part, setPart] = useState<DayPart>('FULL');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState<string | null>(null);
  const [file, setFile] = useState<FileMeta | null>(null);
  const [failures, setFailures] = useState<Record<string, string>>({});

  const type = types.data?.find((t) => t.id === (typeId || types.data?.[0]?.id));
  const oneDay = !!start && start === end;
  const effectivePart: DayPart = oneDay && type?.halfDayAllowed ? part : 'FULL';
  const preview = useQuery({
    queryKey: ['leave', 'preview', start, end, effectivePart],
    queryFn: () => api.leave.preview(start!, end!, effectivePart),
    enabled: !!start && !!end,
  });
  const days = preview.data && start && end ? Number(preview.data.days) : null;
  const balance = balances.find((b) => b.leaveTypeId === type?.id);
  const thisYear = month.today.slice(0, 4);
  // Other plans of this type this year (not the one being edited) already come off the projection.
  const otherPlanned = balance ? Number(balance.planned) - (editing && editing.leaveTypeId === balance.leaveTypeId && editing.startDate.startsWith(thisYear) ? Number(editing.days) : 0) : 0;
  const leftAfter = balance?.trackBalance && days !== null && start?.startsWith(thisYear)
    ? Number(balance.available) - otherPlanned - days : null;

  const clear = () => {
    setEditing(null);
    setStart(null);
    setEnd(null);
    setAwaitingEnd(false);
    setReason('');
    setPart('FULL');
  };
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['leave'] });

  const save = useMutation({
    mutationFn: () => {
      const input = { leaveTypeId: type!.id, startDate: start!, endDate: end!, dayPart: effectivePart, reason: reason || undefined };
      return editing ? api.leave.drafts.update(editing.id, input) : api.leave.drafts.create(input);
    },
    onSuccess: async () => {
      clear();
      await refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.leave.drafts.remove(id),
    onSuccess: async (_, id) => {
      if (editing?.id === id) clear();
      await refresh();
    },
  });
  const submit = useMutation({
    mutationFn: async (ids: string[]) => {
      const failed: Record<string, string> = {};
      for (const id of ids) {
        try {
          await api.leave.drafts.submit(id, ids.length === 1 ? file?.id : null);
        } catch (e) {
          failed[id] = errorText(e);
        }
      }
      return failed;
    },
    onSuccess: async (failed) => {
      setFailures(failed);
      setSubmitting(null);
      setFile(null);
      await refresh();
      await queryClient.invalidateQueries({ queryKey: ['approvals'] });
    },
  });

  const pick = (iso: string) => {
    if (iso < month.today) return;
    save.reset();
    if (!awaitingEnd || !start) {
      setStart(iso);
      setEnd(iso);
      setAwaitingEnd(true);
    } else {
      if (iso < start) setStart(iso);
      else setEnd(iso);
      setAwaitingEnd(false);
    }
  };
  const editDraft = (d: LeaveRequest) => {
    save.reset();
    setEditing(d);
    setTypeId(d.leaveTypeId);
    setStart(d.startDate);
    setEnd(d.endDate);
    setPart(d.dayPart);
    setReason(d.reason ?? '');
    setAwaitingEnd(false);
    month.show(d.startDate);
  };

  // While choosing the end day, the range follows the pointer.
  const rangeEnd = awaitingEnd && start && hover && hover >= start ? hover : end;
  const inRange = (iso: string) => !!start && !!rangeEnd && iso >= start && iso <= rangeEnd;
  const drafts = data.drafts;
  const plannedTotal = drafts.reduce((s, d) => s + Number(d.days), 0);
  const projected = balances.filter((b) => b.trackBalance && (Number(b.planned) > 0 || b.leaveTypeId === type?.id));

  return (
    <div className="lc-layout lc-layout--planner">
      <Card title={t('plan.pickTitle')} action={<MonthNav month={month} />}>
        <p className="hint card-pad lc-instruction" aria-live="polite">
          {awaitingEnd && start ? t('plan.pickLast', { date: longDate(start) }) : t('plan.pickFirst')}
        </p>
        {data.error && <p className="form-error card-pad" role="alert">{errorText(data.error)}</p>}
        <div className="lc-grid lc-grid--compact" aria-label={month.title} onMouseLeave={() => setHover(null)}>
          {weekdayNames().map((d) => <span key={d} className="lc-wd" aria-hidden="true">{d}</span>)}
          {monthGrid(month.year, month.month).map((iso, i) => {
            if (!iso) return <span key={`p${i}`} className="lc-pad" aria-hidden="true" />;
            const list = data.byDate.get(iso) ?? [];
            const holiday = list.find((x) => x.kind === 'HOLIDAY');
            const draft = list.find((x) => x.kind === 'DRAFT');
            const past = iso < month.today;
            const cls = ['lc-cell', !data.isWorkDay(iso) && 'is-off', holiday && 'is-holiday', iso === month.today && 'is-today',
              past && 'is-past', inRange(iso) && 'in-range', iso === start && 'range-start', iso === rangeEnd && 'range-end']
              .filter(Boolean).join(' ');
            return (
              <button key={iso} type="button" className={cls} disabled={past}
                onMouseEnter={() => setHover(iso)}
                onClick={() => (draft && !awaitingEnd ? editDraft(draft.request!) : pick(iso))}
                aria-label={`${longDate(iso)}${holiday ? t('cal.dayLabelHoliday', { name: holiday.label }) : ''}${list.filter((x) => x.kind !== 'HOLIDAY').map((x) => `, ${x.label} (${t(`status.${x.kind as 'DRAFT'}`)})`).join('')}`}>
                <span className="lc-num">{Number(iso.slice(8))}</span>
                {holiday && <span className="lc-holiday">{holiday.label}</span>}
                {list.filter((x) => x.kind !== 'HOLIDAY').slice(0, 2).map((x) => <Chip key={x.key} item={x} />)}
              </button>
            );
          })}
        </div>
      </Card>

      <div className="col">
        <Card title={editing ? t('plan.changeTitle') : t('plan.newTitle')}>
          <form
            className="stack card-pad"
            onSubmit={(e) => {
              e.preventDefault();
              save.mutate();
            }}
          >
            {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
            <div className="lp-dates">
              {start ? (
                <strong>{formatRange(start, end ?? start, effectivePart)}</strong>
              ) : (
                <span className="hint">{t('plan.noDays')}</span>
              )}
              {days !== null && <span>{formatDays(days)}</span>}
            </div>
            <label className="field">
              {t('leave.form.type')}
              <select value={type?.id ?? ''} onChange={(e) => setTypeId(e.target.value)} required>
                {types.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
            </label>
            {oneDay && type?.halfDayAllowed && (
              <div className="segmented" role="radiogroup" aria-label={t('leave.form.howMuch')}>
                {(['FULL', 'AM', 'PM'] as DayPart[]).map((p) => (
                  <label key={p} className={part === p ? 'is-on' : undefined}>
                    <input type="radio" name="plan-part" value={p} checked={part === p} onChange={() => setPart(p)} />
                    {p === 'FULL' ? t('dates.fullDay') : p === 'AM' ? t('dates.morningCap') : t('dates.afternoonCap')}
                  </label>
                ))}
              </div>
            )}
            <label className="field">
              {t('plan.note')} <span className="hint">{t('plan.noteHint')}</span>
              <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} placeholder={t('plan.noteExample')} />
            </label>
            {days === 0 && <p className="hint">{t('plan.noWorkingDays')}</p>}
            {leftAfter !== null && days !== 0 && (
              <p className={leftAfter < 0 ? 'lp-projection is-short' : 'lp-projection'}>
                {leftAfter < 0
                  ? t('plan.tooMany', { n: n(-leftAfter), type: type!.name.toLocaleLowerCase() })
                  : t('plan.leftAfter', { n: n(leftAfter), type: type!.name.toLocaleLowerCase() })}
              </p>
            )}
            <div className="panel-actions">
              <button type="submit" className="btn primary" disabled={!start || !end || awaitingEnd || !type || days === 0 || save.isPending}>
                {editing ? t('common.saveChanges') : t('plan.add')}
              </button>
              {(start || editing) && <button type="button" className="link-button" onClick={clear}>{editing ? t('plan.stopEditing') : t('plan.clear')}</button>}
            </div>
            <p className="hint">{t('plan.private')}</p>
          </form>
        </Card>

        {projected.length > 0 && (
          <Card title={t('plan.balanceTitle')}>
            <ul className="lp-bars">
              {projected.map((b) => {
                const total = Number(b.entitled) + Number(b.carried);
                const used = Number(b.used) + Number(b.pending);
                const planned = Number(b.planned);
                const after = Number(b.available) - planned;
                const pct = (x: number) => `${total > 0 ? Math.max(0, Math.min(100, (x / total) * 100)) : 0}%`;
                return (
                  <li key={b.leaveTypeId}>
                    <div className="lp-bars__head">
                      <strong>{b.name}</strong>
                      <span className={after < 0 ? 'lp-bad' : undefined}>
                        {t('plan.leftNow', { n: n(b.available) })}{planned > 0 && <> → <strong>{n(after)}</strong> {t('plan.afterPlans')}</>}
                      </span>
                    </div>
                    <div className="lp-bar" aria-hidden="true">
                      <span className="lp-bar__used" style={{ width: pct(used) }} />
                      <span className="lp-bar__planned" style={{ width: pct(Math.min(planned, Math.max(0, total - used))) }} />
                    </div>
                    <span className="hint">{t('plan.bar', { used: n(used), planned: n(planned), total: n(total) })}</span>
                  </li>
                );
              })}
            </ul>
          </Card>
        )}

        <Card
          title={t('plan.yourPlan')}
          count={drafts.length}
          action={drafts.length > 1 && (
            <button type="button" className="btn small primary" disabled={submit.isPending} onClick={() => submit.mutate(drafts.map((d) => d.id))}>
              {t('plan.submitAll')}
            </button>
          )}
        >
          {drafts.length === 0 ? (
            <EmptyState icon="calendar" tone="teal" title={t('plan.nothingTitle')}>
              {t('plan.nothingBody')}
            </EmptyState>
          ) : (
            <>
              <List>
                {drafts.map((d) => {
                  const needsFile = !!types.data?.find((t) => t.id === d.leaveTypeId)?.requiresAttachment;
                  return (
                    <li key={d.id} className="lp-draft">
                      <div className="ui-row">
                        <span className="ui-row__lead"><Glyph icon="calendar" tone={toTone(d.tone)} /></span>
                        <span className="ui-row__text">
                          <span className="ui-row__title">{d.typeName}, {formatRange(d.startDate, d.endDate, d.dayPart)}</span>
                          <span className="ui-row__meta">{[formatDays(d.days), d.reason].filter(Boolean).join(' · ')}</span>
                        </span>
                        <span className="ui-row__trail">
                            <button type="button" className="btn small" onClick={() => editDraft(d)}>{t('common.change')}</button>
                            <button type="button" className="btn small primary" disabled={submit.isPending}
                              onClick={() => (needsFile ? setSubmitting(submitting === d.id ? null : d.id) : submit.mutate([d.id]))}>
                              {t('plan.submit')}
                            </button>
                            <button type="button" className="link-button" onClick={() => remove.mutate(d.id)} disabled={remove.isPending}
                              aria-label={t('plan.deleteLabel', { what: `${d.typeName}, ${formatRange(d.startDate, d.endDate, d.dayPart)}` })}>
                              {t('common.delete')}
                            </button>
                        </span>
                      </div>
                      {submitting === d.id && (
                        <div className="lp-draft__file">
                          <FileField label={t('leave.form.docRequired')} required current={file} onChange={setFile} />
                          <button type="button" className="btn small primary" disabled={!file || submit.isPending} onClick={() => submit.mutate([d.id])}>
                            {t('plan.sendForApproval')}
                          </button>
                        </div>
                      )}
                      {failures[d.id] && <p className="form-error lp-draft__error" role="alert">{failures[d.id]}</p>}
                    </li>
                  );
                })}
              </List>
              <p className="hint card-pad">{t('plan.total', { days: formatDays(plannedTotal) })}</p>
            </>
          )}
          {remove.error && <p className="form-error card-pad" role="alert">{errorText(remove.error)}</p>}
        </Card>
      </div>
    </div>
  );
}
