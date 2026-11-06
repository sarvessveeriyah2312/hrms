import { useState, type DragEvent } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import {
  ApiError, type RosterProblem, type RosterShift, type RosterShiftInput, type RosterWeek, type ShiftTemplate, type ShiftTemplateInput,
  type ShiftTone,
} from '@hrms/api-client';
import { api } from '../../lib/api';
import { can, errorText, useAccess } from '../../lib/access';
import { addDays, formatDate, isoDate, relative } from '../../lib/dates';
import { Card, EmptyState, PageHeader, Status, Tabs, type TabItem } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Icon } from '../../components/Icon';
import { hhmm, mondayOf, shiftHours, SHIFT_TONES } from './rosterText';
import './rosters.css';

type Tab = 'roster' | 'templates';

/**
 * /app/attendance/rosters. Roster (attendance:roster): plan a site's week, publish it. Shift templates: read with
 * roster or manage, change with attendance:manage. Site, week and tab are in the URL.
 */
export function RostersPage() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const [params, setParams] = useSearchParams();
  const canRoster = can(user, 'attendance:roster');
  const tabs: (TabItem & { key: Tab })[] = [
    ...(canRoster ? [{ key: 'roster' as const, label: t('roster.tabs.roster'), icon: 'calendar' as const }] : []),
    { key: 'templates' as const, label: t('roster.tabs.templates'), icon: 'clock' as const },
  ];
  const tab = tabs.find((x) => x.key === params.get('tab'))?.key ?? tabs[0].key;
  const setTab = (key: string) => setParams(key === tabs[0].key ? {} : { tab: key }, { replace: true });

  return (
    <>
      <PageHeader title={t('nav.rosters')} subtitle={t(`roster.subtitle.${tab}`)} />
      <Tabs titled label={t('nav.rosters')} value={tab} onChange={setTab} tabs={tabs}>
        {tab === 'roster' ? <RosterTab /> : <TemplatesTab />}
      </Tabs>
    </>
  );
}

// ---------- the week ----------

type Drag = { kind: 'template'; id: string } | { kind: 'shift'; id: string };
/** employeeId null: an open shift (nobody on it yet). */
type Editing = { shift: RosterShift } | { employeeId: string | null; date: string };

function RosterTab() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [today] = useState(() => isoDate(new Date()));
  const sites = useQuery({ queryKey: ['attendance', 'roster', 'sites'], queryFn: api.attendance.rosters.sites });
  const templates = useQuery({ queryKey: ['attendance', 'shift-templates'], queryFn: api.attendance.shiftTemplates.list });
  const siteId = params.get('site') ?? sites.data?.[0]?.id ?? null;
  const week = mondayOf(params.get('week') ?? today);
  const setParam = (name: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(name, value); else next.delete(name);
    setParams(next, { replace: true });
  };
  const roster = useQuery({
    queryKey: ['attendance', 'roster', siteId, week],
    queryFn: () => api.attendance.rosters.week(siteId!, week),
    enabled: !!siteId,
  });
  const [editing, setEditing] = useState<Editing | null>(null);
  const [publishing, setPublishing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['attendance', 'roster', siteId, week] });

  const drop = useMutation({
    // Onto the open row: a template makes an open shift; a shift only moves day (it keeps its person).
    mutationFn: ({ drag, employeeId, date }: { drag: Drag; employeeId: string | null; date: string }) => (drag.kind === 'template'
      ? api.attendance.rosters.addShift({ siteId: siteId!, employeeId, date, templateId: drag.id })
      : api.attendance.rosters.updateShift(drag.id, employeeId ? { employeeId, date } : { date })),
    onSuccess: refresh,
  });
  const copy = useMutation({
    mutationFn: () => api.attendance.rosters.copyPrevious(siteId!, week),
    onSuccess: async (r) => {
      setNotice(t('roster.copied', { copied: r.copied, skipped: r.skipped }));
      await refresh();
    },
  });
  const clear = useMutation({
    mutationFn: () => api.attendance.rosters.clear(siteId!, week),
    onSuccess: async (n) => {
      setNotice(t('roster.cleared', { count: n }));
      await refresh();
    },
  });

  if (sites.data && sites.data.length === 0) {
    return <Card><EmptyState icon="pin" tone="blue" title={t('roster.noSitesTitle')}>{t('roster.noSitesBody')}</EmptyState></Card>;
  }
  const w = roster.data;
  const locked = !!w?.locked;
  const problemsByShift = new Map<string, RosterProblem[]>();
  w?.problems.forEach((p) => problemsByShift.set(p.shiftId, [...(problemsByShift.get(p.shiftId) ?? []), p]));
  const error = sites.error ?? roster.error ?? drop.error ?? copy.error ?? clear.error;

  return (
    <div className="att-stack">
      <div className="att-toolbar">
        <div className="att-toolbar__lead">
          <label className="field roster-site">
            <span className="sr-only">{t('roster.site')}</span>
            <select value={siteId ?? ''} onChange={(e) => setParam('site', e.target.value)}>
              {(sites.data ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
          <h2 className="att-toolbar__title">{t('roster.weekOf', { date: formatDate(week, { day: 'numeric', month: 'long', year: 'numeric' }) })}</h2>
          {w && <WeekStatus week={w} />}
        </div>
        <div className="att-toolbar__end">
          <div className="month-picker" role="group" aria-label={t('roster.week')}>
            <button type="button" className="icon-pill" onClick={() => setParam('week', addDays(week, -7))} aria-label={t('roster.prevWeek')}>
              <Icon name="chevron" size={14} style={{ transform: 'rotate(180deg)' }} />
            </button>
            <input type="date" className="input" value={week} aria-label={t('roster.week')} onChange={(e) => e.target.value && setParam('week', mondayOf(e.target.value))} />
            <button type="button" className="icon-pill" onClick={() => setParam('week', addDays(week, 7))} aria-label={t('roster.nextWeek')}>
              <Icon name="chevron" size={14} />
            </button>
          </div>
        </div>
      </div>

      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {notice && (
        <p className="notice notice--action" role="status">
          <span>{notice}</span>
          <button type="button" className="btn small" onClick={() => setNotice(null)}>{t('common.ok')}</button>
        </p>
      )}
      {locked && <p className="notice" role="status">{t('roster.locked')}</p>}

      <Card
        title={w?.site.name ?? t('roster.tabs.roster')}
        count={w?.shifts.length}
        action={w && !locked && (
          <span className="roster-actions">
            <button type="button" className="btn small" disabled={copy.isPending} onClick={() => copy.mutate()}>{t('roster.copyLast')}</button>
            <button type="button" className="btn small" disabled={clear.isPending || w.shifts.length === 0} onClick={() => clear.mutate()}>{t('roster.clear')}</button>
            <button type="button" className="btn small primary" disabled={w.shifts.length === 0 && !w.status.published} onClick={() => setPublishing(true)}>
              {w.status.published ? t('roster.republish') : t('roster.publish')}
            </button>
          </span>
        )}
      >
        {!locked && (templates.data?.filter((x) => x.active).length ?? 0) > 0 && (
          <div className="roster-palette card-pad">
            <span className="hint">{t('roster.dragHint')}</span>
            {templates.data!.filter((x) => x.active).map((tpl) => (
              <span key={tpl.id} className={`shift-chip shift--${tpl.tone}`} draggable
                onDragStart={(e) => e.dataTransfer.setData('text/plain', JSON.stringify({ kind: 'template', id: tpl.id } satisfies Drag))}
                title={`${tpl.name} · ${hhmm(tpl.startTime)}–${hhmm(tpl.endTime)}`}>
                <strong>{tpl.code}</strong> {hhmm(tpl.startTime)}–{hhmm(tpl.endTime)}
              </span>
            ))}
          </div>
        )}
        {w && w.people.length === 0 && w.shifts.length === 0 ? (
          <EmptyState icon="users" tone="blue" title={t('roster.nobodyTitle')}>{t('roster.nobodyBody')}</EmptyState>
        ) : (
          <div className="roster-scroll">
            <table className="roster-grid">
              <caption className="sr-only">{t('roster.caption', { site: w?.site.name ?? '' })}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('roster.person')}</th>
                  {(w?.days ?? []).map((d) => (
                    <th key={d} scope="col" className={d === today ? 'is-today' : undefined}>
                      <span>{formatDate(d, { weekday: 'short' })}</span> <span className="muted">{formatDate(d, { day: 'numeric', month: 'short' })}</span>
                      {w!.holidays[d] && <small className="roster-holiday">{w!.holidays[d]}</small>}
                    </th>
                  ))}
                  <th scope="col" className="roster-total">{t('roster.hours')}</th>
                </tr>
              </thead>
              <tbody>
                {roster.isPending && <tr><td colSpan={9} className="muted">{t('common.loading')}</td></tr>}
                {w && (
                  <tr className="roster-open">
                    <th scope="row"><strong>{t('roster.openShifts')}</strong><small className="muted">{t('roster.openHint')}</small></th>
                    {w.days.map((d) => (
                      <Cell key={d} day={d} locked={locked} restDay={false} shifts={w.shifts.filter((s) => s.employeeId === null && s.date === d)}
                        problems={problemsByShift} onDrop={(drag) => drop.mutate({ drag, employeeId: null, date: d })}
                        onAdd={() => setEditing({ employeeId: null, date: d })} onOpen={(s) => setEditing({ shift: s })}
                        label={t('roster.addOpen', { date: formatDate(d, { weekday: 'long', day: 'numeric', month: 'long' }) })} />
                    ))}
                    <td className="roster-total" />
                  </tr>
                )}
                {w?.people.map((p) => {
                  const mine = w.shifts.filter((s) => s.employeeId === p.id);
                  const minutes = mine.reduce((n, s) => n + shiftHours(s.startTime, s.endTime, s.breakMin), 0);
                  return (
                    <tr key={p.id}>
                      <th scope="row">
                        <strong>{p.fullName}</strong>
                        <small className="muted">{[p.employeeNo, p.department].filter(Boolean).join(' · ')}{!p.member && ` · ${t('roster.notMember')}`}</small>
                      </th>
                      {w.days.map((d) => (
                        <Cell key={d} day={d} locked={locked} restDay={!p.workDays.includes(((new Date(`${d}T00:00`).getDay() + 6) % 7) + 1)}
                          leave={p.leave[d]} availability={availabilityText(t, p.availability, d)} shifts={mine.filter((s) => s.date === d)} problems={problemsByShift}
                          onDrop={(drag) => drop.mutate({ drag, employeeId: p.id, date: d })}
                          onAdd={() => setEditing({ employeeId: p.id, date: d })}
                          onOpen={(s) => setEditing({ shift: s })}
                          label={t('roster.cellLabel', { name: p.fullName, date: formatDate(d, { weekday: 'long', day: 'numeric', month: 'long' }) })} />
                      ))}
                      <td className="roster-total">{minutes ? t('timesheet.hm', { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, '0') }) : '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {w && w.problems.length > 0 && <ProblemsCard problems={w.problems} />}

      {editing && w && (
        <ShiftPanel key={'shift' in editing ? editing.shift.id : `${editing.employeeId}${editing.date}`} week={w} editing={editing}
          templates={templates.data ?? []} onClose={() => setEditing(null)} onSaved={refresh} />
      )}
      {publishing && w && <PublishPanel week={w} onClose={() => setPublishing(false)} onDone={refresh} />}
    </div>
  );
}

function WeekStatus({ week: w }: { week: RosterWeek }) {
  const { t } = useTranslation();
  if (!w.status.published) return <Status tone="grey">{w.shifts.length ? t('roster.status.draft') : t('roster.status.empty')}</Status>;
  if (w.status.changed) return <Status tone="amber">{t('roster.status.changed')}</Status>;
  return <Status tone="green">{t('roster.status.published', { when: relative(w.status.publishedAt!) })}</Status>;
}

/** "Not available", "Available 09:00–17:00"; nothing for any time. */
function availabilityText(t: TFunction, days: RosterWeek['people'][number]['availability'], date: string) {
  const weekday = ((new Date(`${date}T00:00`).getDay() + 6) % 7) + 1;
  const a = days.find((x) => x.weekday === weekday);
  if (!a || a.mode === 'ANY') return undefined;
  if (a.mode === 'NONE') return t('roster.unavailable');
  return t('roster.availableAt', { times: a.windows.map((w) => `${hhmm(w.start)}–${hhmm(w.end)}`).join(', ') });
}

function Cell({ day, locked, restDay, leave, availability, shifts, problems, onDrop, onAdd, onOpen, label }: {
  day: string; locked: boolean; restDay: boolean; leave?: string; availability?: string; shifts: RosterShift[]; problems: Map<string, RosterProblem[]>;
  onDrop: (drag: Drag) => void; onAdd: () => void; onOpen: (s: RosterShift) => void; label: string;
}) {
  const { t } = useTranslation();
  const [over, setOver] = useState(false);
  const accept = (e: DragEvent) => {
    if (locked) return;
    e.preventDefault();
    setOver(true);
  };
  const dropped = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    try {
      const drag = JSON.parse(e.dataTransfer.getData('text/plain')) as Drag;
      if (drag?.id) onDrop(drag);
    } catch { /* not ours */ }
  };
  const cls = ['roster-cell', restDay && 'is-rest', over && 'is-over'].filter(Boolean).join(' ');
  return (
    <td className={cls} onDragOver={accept} onDragLeave={() => setOver(false)} onDrop={dropped} data-day={day}>
      {leave && <small className="roster-leave">{leave}</small>}
      {availability && <small className="roster-avail">{availability}</small>}
      {shifts.map((s) => {
        const issues = problems.get(s.id) ?? [];
        const level = issues.some((p) => p.severity === 'ERROR') ? 'has-error' : issues.length ? 'has-warning' : '';
        return (
          <button key={s.id} type="button" className={`shift-chip shift--${s.tone} ${level}`} draggable={!locked}
            onDragStart={(e) => e.dataTransfer.setData('text/plain', JSON.stringify({ kind: 'shift', id: s.id } satisfies Drag))}
            onClick={() => onOpen(s)} title={s.note ?? undefined}
            aria-label={t('roster.shiftLabel', { code: s.code ?? '', start: hhmm(s.startTime), end: hhmm(s.endTime) })}>
            {s.code && <strong>{s.code}</strong>} {hhmm(s.startTime)}–{hhmm(s.endTime)}
          </button>
        );
      })}
      {!locked && shifts.length < 3 && (
        <button type="button" className="roster-add" onClick={onAdd} aria-label={label}>
          <Icon name="plus" size={12} />
        </button>
      )}
    </td>
  );
}

function ProblemsCard({ problems }: { problems: RosterProblem[] }) {
  const { t } = useTranslation();
  return (
    <Card title={t('roster.problems')} count={problems.length}>
      <ul className="roster-problems card-pad">
        {problems.map((p, i) => (
          <li key={`${p.shiftId}-${p.kind}-${i}`} className={p.severity === 'ERROR' ? 'is-error' : 'is-warning'}>
            <Status tone={p.severity === 'ERROR' ? 'red' : 'amber'}>{t(`roster.severity.${p.severity}`)}</Status>{' '}
            <strong>{p.employeeName}</strong>, {formatDate(p.date, { weekday: 'short', day: 'numeric', month: 'short' })}: {t(`roster.problem.${p.kind}`, { detail: p.detail ?? '' })}
          </li>
        ))}
      </ul>
    </Card>
  );
}

/** Add a shift (from a template or custom times) or change, move or remove one. */
function ShiftPanel({ week, editing, templates, onClose, onSaved }: {
  week: RosterWeek; editing: Editing; templates: ShiftTemplate[]; onClose: () => void; onSaved: () => Promise<unknown>;
}) {
  const { t } = useTranslation();
  const shift = 'shift' in editing ? editing.shift : null;
  // '' is an open shift (nobody on it yet).
  const [employeeId, setEmployeeId] = useState<string>(shift ? shift.employeeId ?? '' : 'employeeId' in editing ? editing.employeeId ?? '' : '');
  const canBeOpen = !shift || shift.employeeId === null;
  const [date, setDate] = useState(shift?.date ?? ('date' in editing ? editing.date : week.weekStart));
  const active = templates.filter((x) => x.active);
  const [templateId, setTemplateId] = useState<string>(shift?.templateId ?? (shift ? '' : active[0]?.id ?? ''));
  const tpl = templates.find((x) => x.id === templateId);
  const [start, setStart] = useState(hhmm(shift?.startTime ?? tpl?.startTime ?? '09:00'));
  const [end, setEnd] = useState(hhmm(shift?.endTime ?? tpl?.endTime ?? '18:00'));
  const [breakMin, setBreakMin] = useState(String(shift?.breakMin ?? tpl?.breakMin ?? 60));
  const [graceMin, setGraceMin] = useState(String(shift?.graceMin ?? tpl?.graceMin ?? 5));
  const [note, setNote] = useState(shift?.note ?? '');
  const person = week.people.find((p) => p.id === employeeId);
  const pick = (id: string) => {
    setTemplateId(id);
    const x = templates.find((y) => y.id === id);
    if (x) {
      setStart(hhmm(x.startTime));
      setEnd(hhmm(x.endTime));
      setBreakMin(String(x.breakMin));
      setGraceMin(String(x.graceMin));
    }
  };
  const input = (): RosterShiftInput => ({
    siteId: week.site.id, employeeId: employeeId || null, date, templateId: templateId || null,
    startTime: start, endTime: end, breakMin: Number(breakMin), graceMin: Number(graceMin), note: note.trim() || null,
  });
  const save = useMutation({
    mutationFn: () => (shift ? api.attendance.rosters.updateShift(shift.id, input()) : api.attendance.rosters.addShift(input())),
    onSuccess: async () => { await onSaved(); onClose(); },
  });
  const remove = useMutation({
    mutationFn: () => api.attendance.rosters.removeShift(shift!.id),
    onSuccess: async () => { await onSaved(); onClose(); },
  });
  const valid = (!!employeeId || canBeOpen) && !!date && !!start && !!end && end > start;

  return (
    <Panel title={shift ? t('roster.editShift') : person ? t('roster.addShift', { name: person.fullName }) : t('roster.addOpenTitle')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        {(save.error || remove.error) && <p className="form-error" role="alert">{errorText(save.error ?? remove.error)}</p>}
        <div className="field-row">
          <label className="field">
            {t('roster.person')}
            <select value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
              {canBeOpen && <option value="">{t('roster.openOption')}</option>}
              {week.people.map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
            </select>
          </label>
          <label className="field">
            {t('roster.day')}
            <select value={date} onChange={(e) => setDate(e.target.value)}>
              {week.days.map((d) => <option key={d} value={d}>{formatDate(d, { weekday: 'long', day: 'numeric', month: 'short' })}</option>)}
            </select>
          </label>
        </div>
        {person?.leave[date] && <p className="notice">{t('roster.onLeave', { leave: person.leave[date] })}</p>}
        <label className="field">
          {t('roster.template')}
          <select value={templateId} onChange={(e) => pick(e.target.value)}>
            <option value="">{t('roster.custom')}</option>
            {active.map((x) => <option key={x.id} value={x.id}>{`${x.code} · ${x.name} (${hhmm(x.startTime)}–${hhmm(x.endTime)})`}</option>)}
          </select>
        </label>
        <div className="field-row">
          <label className="field">{t('roster.start')}<input type="time" value={start} onChange={(e) => setStart(e.target.value)} required /></label>
          <label className="field">{t('roster.end')}<input type="time" value={end} onChange={(e) => setEnd(e.target.value)} required /></label>
        </div>
        {start && end && end <= start && <p className="form-error" role="alert">{t('roster.endAfterStart')}</p>}
        <div className="field-row">
          <label className="field">{t('roster.breakMin')}<input type="number" min={0} max={240} step={5} value={breakMin} onChange={(e) => setBreakMin(e.target.value)} /></label>
          <label className="field">{t('roster.graceMin')}<input type="number" min={0} max={120} step={1} value={graceMin} onChange={(e) => setGraceMin(e.target.value)} /></label>
        </div>
        <label className="field">
          {t('roster.note')}
          <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} placeholder={t('roster.noteHint')} />
        </label>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!valid || save.isPending}>{shift ? t('common.save') : t('roster.add')}</button>
          {shift && <button type="button" className="btn reject" disabled={remove.isPending} onClick={() => remove.mutate()}>{t('roster.remove')}</button>}
        </div>
      </form>
    </Panel>
  );
}

/** Publish (checking first; warnings need confirming) or take the published week back. */
function PublishPanel({ week: w, onClose, onDone }: { week: RosterWeek; onClose: () => void; onDone: () => Promise<unknown> }) {
  const { t } = useTranslation();
  const [warnings, setWarnings] = useState<RosterProblem[] | null>(null);
  const publish = useMutation({
    mutationFn: (acknowledge: boolean) => api.attendance.rosters.publish(w.site.id, w.weekStart, acknowledge),
    onSuccess: async () => { await onDone(); onClose(); },
    onError: (e) => {
      if (e instanceof ApiError && e.problem.code === 'ROSTER_HAS_WARNINGS') setWarnings(e.problem.problems ?? []);
    },
  });
  const unpublish = useMutation({
    mutationFn: () => api.attendance.rosters.unpublish(w.site.id, w.weekStart),
    onSuccess: async () => { await onDone(); onClose(); },
  });
  const errors = w.problems.filter((p) => p.severity === 'ERROR');
  const failed = publish.error instanceof ApiError && publish.error.problem.code === 'ROSTER_HAS_WARNINGS' ? null : publish.error ?? unpublish.error;
  const people = new Set(w.shifts.map((s) => s.employeeId)).size;

  return (
    <Panel title={t('roster.publishTitle', { site: w.site.name, date: formatDate(w.weekStart, { day: 'numeric', month: 'short' }) })} onClose={onClose}>
      <div className="stack">
        {failed && <p className="form-error" role="alert">{errorText(failed)}</p>}
        <p>{t('roster.publishSummary', { shifts: w.shifts.length, people })}</p>
        <p className="hint">{t('roster.publishHint')}</p>
        {errors.length > 0 ? (
          <>
            <p className="form-error" role="alert">{t('roster.fixErrors')}</p>
            <ProblemsCard problems={errors} />
          </>
        ) : warnings ? (
          <>
            <p className="notice" role="status">{t('roster.confirmWarnings', { count: warnings.length })}</p>
            <ProblemsCard problems={warnings} />
            <div className="panel-actions">
              <button type="button" className="btn primary" disabled={publish.isPending} onClick={() => publish.mutate(true)}>{t('roster.publishAnyway')}</button>
            </div>
          </>
        ) : (
          <div className="panel-actions">
            <button type="button" className="btn primary" disabled={publish.isPending || w.shifts.length === 0} onClick={() => publish.mutate(false)}>
              {w.status.published ? t('roster.republish') : t('roster.publish')}
            </button>
            {w.status.published && (
              <button type="button" className="btn reject" disabled={unpublish.isPending} onClick={() => unpublish.mutate()}>{t('roster.unpublish')}</button>
            )}
          </div>
        )}
        {w.status.published && <p className="hint">{t('roster.unpublishHint')}</p>}
      </div>
    </Panel>
  );
}

// ---------- shift templates ----------

const EMPTY_TEMPLATE: ShiftTemplateInput = { name: '', code: '', startTime: '09:00', endTime: '18:00', breakMin: 60, graceMin: 5, tone: 'blue', active: true };

function TemplatesTab() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const manage = can(user, 'attendance:manage');
  const list = useQuery({ queryKey: ['attendance', 'shift-templates'], queryFn: api.attendance.shiftTemplates.list });
  const [editing, setEditing] = useState<ShiftTemplate | 'new' | null>(null);

  return (
    <>
      <Card title={t('roster.tabs.templates')} count={list.data?.length}
        action={manage && <button type="button" className="btn small primary" onClick={() => setEditing('new')}><Icon name="plus" size={14} />{t('roster.newTemplate')}</button>}>
        {list.isError && <p className="form-error card-pad" role="alert">{errorText(list.error)}</p>}
        {list.data && list.data.length === 0 ? (
          <EmptyState icon="clock" tone="blue" title={t('roster.noTemplatesTitle')}
            action={manage && <button type="button" className="btn primary" onClick={() => setEditing('new')}>{t('roster.newTemplate')}</button>}>
            {t('roster.noTemplatesBody')}
          </EmptyState>
        ) : (
          <ul className="ui-list">
            {(list.data ?? []).map((x) => (
              <li key={x.id} className="ui-row">
                <span className="ui-row__lead"><span className={`shift-chip shift--${x.tone}`}><strong>{x.code}</strong></span></span>
                <span className="ui-row__text">
                  <span className="ui-row__title">{x.name}</span>
                  <span className="ui-row__meta">
                    {[`${hhmm(x.startTime)}–${hhmm(x.endTime)}`, t('roster.breakShort', { n: x.breakMin }), t('roster.graceShort', { n: x.graceMin })].join(' · ')}
                  </span>
                </span>
                <span className="ui-row__trail">
                  {!x.active && <Status tone="grey">{t('ls.retired')}</Status>}
                  {manage && <button type="button" className="btn small" onClick={() => setEditing(x)}>{t('common.edit')}</button>}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      {editing && <TemplatePanel template={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function TemplatePanel({ template, onClose }: { template: ShiftTemplate | null; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [input, setInput] = useState<ShiftTemplateInput>(template
    ? { ...template, startTime: hhmm(template.startTime), endTime: hhmm(template.endTime) } : EMPTY_TEMPLATE);
  const set = <K extends keyof ShiftTemplateInput>(k: K, v: ShiftTemplateInput[K]) => setInput((x) => ({ ...x, [k]: v }));
  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: ['attendance', 'shift-templates'] });
    onClose();
  };
  const save = useMutation({
    mutationFn: () => (template ? api.attendance.shiftTemplates.update(template.id, input) : api.attendance.shiftTemplates.create(input)),
    onSuccess: done,
  });
  const remove = useMutation({ mutationFn: () => api.attendance.shiftTemplates.remove(template!.id), onSuccess: done });
  const valid = input.name.trim() && input.code.trim() && input.endTime > input.startTime;

  return (
    <Panel title={template ? template.name : t('roster.newTemplate')} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        {(save.error || remove.error) && <p className="form-error" role="alert">{errorText(save.error ?? remove.error)}</p>}
        <div className="field-row">
          <label className="field">{t('roster.templateName')}<input value={input.name} onChange={(e) => set('name', e.target.value)} maxLength={60} required placeholder={t('roster.templateNameHint')} /></label>
          <label className="field roster-code">{t('roster.code')}<input value={input.code} onChange={(e) => set('code', e.target.value.toUpperCase())} maxLength={4} required placeholder="AM" /></label>
        </div>
        <div className="field-row">
          <label className="field">{t('roster.start')}<input type="time" value={input.startTime} onChange={(e) => set('startTime', e.target.value)} required /></label>
          <label className="field">{t('roster.end')}<input type="time" value={input.endTime} onChange={(e) => set('endTime', e.target.value)} required /></label>
        </div>
        {input.endTime <= input.startTime && <p className="form-error" role="alert">{t('roster.endAfterStart')}</p>}
        <div className="field-row">
          <label className="field">{t('roster.breakMin')}<input type="number" min={0} max={240} step={5} value={input.breakMin} onChange={(e) => set('breakMin', Number(e.target.value))} /></label>
          <label className="field">{t('roster.graceMin')}<input type="number" min={0} max={120} value={input.graceMin} onChange={(e) => set('graceMin', Number(e.target.value))} /></label>
        </div>
        <span className="wf-label" id="tpl-tone">{t('roster.colour')}</span>
        <div className="roster-tones" role="radiogroup" aria-labelledby="tpl-tone">
          {SHIFT_TONES.map((tone: ShiftTone) => (
            <label key={tone} className={`shift-chip shift--${tone} ${input.tone === tone ? 'is-on' : ''}`}>
              <input type="radio" name="tpl-tone" className="sr-only" checked={input.tone === tone} onChange={() => set('tone', tone)} />
              {t(`roster.tones.${tone}`)}
            </label>
          ))}
        </div>
        {template && (
          <label className="check-line"><input type="checkbox" checked={!input.active} onChange={(e) => set('active', !e.target.checked)} />{t('roster.retireTemplate')}</label>
        )}
        <p className="hint">{t('roster.templateHint')}</p>
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!valid || save.isPending}>{t('common.save')}</button>
          {template && <button type="button" className="btn reject" disabled={remove.isPending} onClick={() => remove.mutate()}>{t('roster.deleteTemplate')}</button>}
        </div>
      </form>
    </Panel>
  );
}
