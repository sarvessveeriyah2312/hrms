import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { AttendanceCorrection, TimesheetDay, TimesheetTotals } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, SCOPE_LABEL } from '../../lib/access';
import { addDays, formatDate, isoDate } from '../../lib/dates';
import { useUrlFilters, type FilterDef } from '../../lib/filters';
import { PAGE_SIZES } from '../../lib/paging';
import { Card, StatCard, Status } from '../../components/app/kit';
import { FilterBar } from '../../components/app/FilterBar';
import { Table } from '../../components/app/Table';
import { Icon } from '../../components/Icon';
import { CorrectionForm, CorrectionStatusChip, MyCorrections, type CorrectionTarget } from './Corrections';
import './attendance.css';

const hhmm = (time: string | null) => (time ? time.slice(0, 5) : '—');

/** 485 → "8h 05m" (in the current language). */
function duration(t: TFunction, minutes: number) {
  if (minutes <= 0) return '—';
  return t('timesheet.hm', { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, '0') });
}

/** "2026-11" → first and last day of that month. */
function monthRange(month: string) {
  const [y, m] = month.split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, '0')}` };
}

function shiftMonth(month: string, by: number) {
  const [y, m] = month.split('-').map(Number);
  const d = new Date(y, m - 1 + by, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** The month in the URL (?month=YYYY-MM), defaulting to this month; never after this month. */
function useMonth(filters: { param: (n: string) => string | null; setParam: (n: string, v: string | null) => void }) {
  const [thisMonth] = useState(() => isoDate(new Date()).slice(0, 7));
  const raw = filters.param('month');
  const month = raw && /^\d{4}-\d{2}$/.test(raw) && raw <= thisMonth ? raw : thisMonth;
  const set = (m: string) => filters.setParam('month', !m || m >= thisMonth ? null : m);
  return { month, thisMonth, set };
}

function MonthPicker({ month, thisMonth, onChange }: { month: string; thisMonth: string; onChange: (m: string) => void }) {
  const { t } = useTranslation();
  return (
    <div className="month-picker" role="group" aria-label={t('timesheet.month')}>
      <button type="button" className="icon-pill" onClick={() => onChange(shiftMonth(month, -1))} aria-label={t('timesheet.prevMonth')}>
        <Icon name="chevron" size={14} style={{ transform: 'rotate(180deg)' }} />
      </button>
      <input type="month" className="input" value={month} max={thisMonth} aria-label={t('timesheet.month')}
        onChange={(e) => e.target.value && onChange(e.target.value)} />
      <button type="button" className="icon-pill" onClick={() => onChange(shiftMonth(month, 1))} disabled={month >= thisMonth}
        aria-label={t('timesheet.nextMonth')}>
        <Icon name="chevron" size={14} />
      </button>
    </div>
  );
}

// ---------- everyone (managers) ----------

/** Attendance → Team timesheets: totals per person for a month; pick someone to see their days. */
export function TeamTimesheets() {
  const { t } = useTranslation();
  const defs: FilterDef[] = [
    { key: 'q', label: t('common.search'), kind: 'search', pinned: true, placeholder: t('timesheet.search') },
    { key: 'issue', label: t('timesheet.issue'), kind: 'select', pinned: true, allLabel: t('timesheet.anyone'),
      options: (['late', 'early', 'missed', 'absent'] as const).map((k) => ({ value: k, label: t(`timesheet.issues.${k}`) })) },
  ];
  const filters = useUrlFilters(defs);
  const { month, thisMonth, set: setMonth } = useMonth(filters);
  const person = filters.param('person');
  const size = Number(filters.param('size'));
  const pageSize = PAGE_SIZES.includes(size) ? size : 25;

  const team = useQuery({
    queryKey: ['attendance', 'timesheets', month],
    queryFn: () => api.attendance.timesheets(monthRange(month)),
    enabled: !person,
  });

  if (person) {
    return (
      <PersonSheet employeeId={person} month={month} thisMonth={thisMonth} onMonth={setMonth}
        onBack={() => filters.setParam('person', null)} />
    );
  }

  const words = filters.values.q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const issue = filters.values.issue;
  const rows = (team.data?.people ?? []).filter((p) => {
    const text = [p.fullName, p.employeeNo, p.department, p.site].filter(Boolean).join(' ').toLowerCase();
    if (!words.every((w) => text.includes(w))) return false;
    const x = p.totals;
    return issue === 'late' ? x.lateCount > 0 : issue === 'early' ? x.earlyCount > 0
      : issue === 'missed' ? x.missedOut > 0 : issue === 'absent' ? x.absent > 0 : true;
  });
  const sum = (k: keyof TimesheetTotals) => rows.reduce((n, p) => n + Number(p.totals[k]), 0);

  return (
    <>
      <div className="att-toolbar">
        <h2 className="att-toolbar__title">{formatDate(`${month}-01`, { month: 'long', year: 'numeric' })}</h2>
        <MonthPicker month={month} thisMonth={thisMonth} onChange={setMonth} />
      </div>
      {team.isError && <p className="form-error" role="alert">{errorText(team.error)}</p>}
      {team.data?.truncated && <p className="notice" role="status">{t('timesheet.truncated', { n: team.data.people.length })}</p>}

      <div className="grid grid--stats">
        <StatCard icon="clock" label={t('timesheet.hoursWorked')} value={team.data ? duration(t, sum('workedMin')) : '–'}
          note={team.data && t('timesheet.nPeople', { count: rows.length })} />
        <StatCard icon="users" label={t('timesheet.late')} value={team.data ? sum('lateCount') : '–'}
          note={team.data && t('timesheet.timesTotal', { time: duration(t, sum('lateMin')) })} />
        <StatCard icon="sun" label={t('timesheet.missedOut')} value={team.data ? sum('missedOut') : '–'} note={t('timesheet.missedNote')} />
        <StatCard icon="calendar" label={t('timesheet.absent')} value={team.data ? sum('absent') : '–'}
          note={team.data && t('timesheet.leaveDays', { n: sum('leaveDays') })} />
      </div>

      <Card title={t('timesheet.everyone')} count={team.data ? rows.length : undefined}
        action={team.data && <Status tone="blue">{SCOPE_LABEL[team.data.scope]}</Status>}>
        <div className="card-pad"><FilterBar filters={filters} label={t('timesheet.filters')} /></div>
        <Table
          label={t('timesheet.everyone')}
          rows={rows}
          rowKey={(p) => p.employeeId}
          loading={team.isPending}
          empty={(team.data?.people.length ?? 0) === 0 ? t('today.nobody') : t('today.noMatch')}
          resetKey={`${month}-${filters.key}`}
          pageSize={pageSize}
          onPageSizeChange={(n) => filters.setParam('size', n === 25 ? null : String(n))}
          columns={[
            { key: 'name', header: t('today.person'), rowHeader: true, cell: (p) => (
              <button type="button" className="link-button today-person" onClick={() => filters.setParam('person', p.employeeId)}>
                <span>{p.fullName}</span>
                <span className="muted">{[p.employeeNo, p.department, p.site].filter(Boolean).join(' · ')}</span>
              </button>
            ) },
            { key: 'days', header: t('timesheet.daysWorked'), align: 'end', cell: (p) => t('timesheet.ofDays', { n: p.totals.daysWorked, of: p.totals.expectedDays }) },
            { key: 'hours', header: t('timesheet.hours'), align: 'end', cell: (p) => duration(t, p.totals.workedMin) },
            { key: 'overtime', header: t('overtime.column'), align: 'end', cell: (p) => duration(t, p.totals.overtimeMin) },
            { key: 'late', header: t('timesheet.late'), align: 'end', cell: (p) => countAndTime(t, p.totals.lateCount, p.totals.lateMin),
              cellClass: (p) => (p.totals.lateCount > 0 ? 'is-warning' : undefined) },
            { key: 'early', header: t('timesheet.leftEarly'), align: 'end', cell: (p) => countAndTime(t, p.totals.earlyCount, p.totals.earlyMin),
              cellClass: (p) => (p.totals.earlyCount > 0 ? 'is-warning' : undefined) },
            { key: 'missed', header: t('timesheet.missedOut'), align: 'end', cell: (p) => p.totals.missedOut || '—',
              cellClass: (p) => (p.totals.missedOut > 0 ? 'is-negative' : undefined) },
            { key: 'absent', header: t('timesheet.absent'), align: 'end', cell: (p) => p.totals.absent || '—',
              cellClass: (p) => (p.totals.absent > 0 ? 'is-negative' : undefined) },
            { key: 'leave', header: t('timesheet.leave'), align: 'end', cell: (p) => Number(p.totals.leaveDays) || '—' },
          ]}
        />
      </Card>
    </>
  );
}

function countAndTime(t: TFunction, count: number, minutes: number) {
  return count > 0 ? t('timesheet.countTime', { n: count, time: duration(t, minutes) }) : '—';
}

// ---------- one person ----------

/**
 * Attendance → My timesheet, with corrections: "Fix" on a day asks for its clock-in or clock-out to be corrected;
 * My corrections lists the requests (the open one is in the URL as ?correction=, so notifications can link to it).
 */
export function MyTimesheet() {
  const filters = useUrlFilters([]);
  const { month, thisMonth, set } = useMonth(filters);
  const [form, setForm] = useState<CorrectionTarget | null>(null);
  const openId = filters.param('correction');
  return (
    <div className="att-stack">
      <PersonSheet employeeId="me" month={month} thisMonth={thisMonth} onMonth={set}
        onFix={(day) => setForm({ day })} onOpenCorrection={(id) => filters.setParam('correction', id)} />
      <MyCorrections openId={openId} onOpen={(id) => filters.setParam('correction', id)}
        onEdit={(c: AttendanceCorrection) => setForm({ editing: c })} />
      {form && <CorrectionForm target={form} onClose={() => setForm(null)} />}
    </div>
  );
}

function PersonSheet({ employeeId, month, thisMonth, onMonth, onBack, onFix, onOpenCorrection }: {
  employeeId: string; month: string; thisMonth: string; onMonth: (m: string) => void; onBack?: () => void;
  /** Your own timesheet: ask to correct a day, or open the request already made for it. */
  onFix?: (day: TimesheetDay) => void; onOpenCorrection?: (id: string) => void;
}) {
  const { t } = useTranslation();
  const sheet = useQuery({
    queryKey: ['attendance', 'timesheet', employeeId, month],
    queryFn: () => api.attendance.timesheet(employeeId, monthRange(month)),
  });
  const s = sheet.data;
  const x = s?.totals;

  return (
    <>
      <div className="att-toolbar">
        <div className="att-toolbar__lead">
          {onBack && (
            <button type="button" className="btn" onClick={onBack}>
              <Icon name="chevron" size={14} style={{ transform: 'rotate(180deg)' }} />{t('timesheet.everyone')}
            </button>
          )}
          <h2 className="att-toolbar__title">
            {onBack && s ? `${s.fullName} · ` : ''}{formatDate(`${month}-01`, { month: 'long', year: 'numeric' })}
          </h2>
        </div>
        <MonthPicker month={month} thisMonth={thisMonth} onChange={onMonth} />
      </div>
      {sheet.isError && <p className="form-error" role="alert">{errorText(sheet.error)}</p>}

      <div className="grid grid--stats">
        <StatCard icon="clock" label={t('timesheet.hoursWorked')} value={x ? duration(t, x.workedMin) : '–'}
          note={x && [t('timesheet.ofDaysLong', { n: x.daysWorked, of: x.expectedDays }),
            x.overtimeMin > 0 && t('overtime.onDay', { time: duration(t, x.overtimeMin) })].filter(Boolean).join(' · ')} />
        <StatCard icon="users" label={t('timesheet.late')} value={x ? x.lateCount : '–'}
          note={x && t('timesheet.timesTotal', { time: duration(t, x.lateMin) })} />
        <StatCard icon="sun" label={t('timesheet.leftEarly')} value={x ? x.earlyCount : '–'}
          note={x && t('timesheet.timesTotal', { time: duration(t, x.earlyMin) })} />
        <StatCard icon="calendar" label={t('timesheet.missedOut')} value={x ? x.missedOut : '–'}
          note={x && t('timesheet.absentLeave', { absent: x.absent, leave: Number(x.leaveDays) })} />
      </div>

      <Card title={s && onBack ? s.fullName : t('timesheet.days')} count={s?.days.length}>
        <Table
          label={t('timesheet.days')}
          rows={s ? [...s.days].reverse() : []}
          rowKey={(d) => d.date}
          loading={sheet.isPending}
          empty={t('timesheet.noDays')}
          pageSize={62}
          pageSizes={[]}
          columns={[
            { key: 'date', header: t('timesheet.date'), rowHeader: true, cell: (d) => formatDate(d.date, { weekday: 'short', day: 'numeric', month: 'short' }),
              cellClass: (d) => (d.offReason ? 'muted' : undefined) },
            { key: 'status', header: t('today.status'), cell: (d) => <DayChip day={d} /> },
            { key: 'clock', header: t('timesheet.clockIns'), cell: (d) => <Sessions day={d} /> },
            { key: 'worked', header: t('timesheet.worked'), align: 'end', cell: (d) => duration(t, d.workedMin) },
            { key: 'late', header: t('timesheet.late'), align: 'end', cell: (d) => (d.lateMin ? t('timesheet.min', { n: d.lateMin }) : '—'),
              cellClass: (d) => (d.lateMin ? 'is-warning' : undefined) },
            { key: 'early', header: t('timesheet.leftEarly'), align: 'end', cell: (d) => (d.earlyMin ? t('timesheet.min', { n: d.earlyMin }) : '—'),
              cellClass: (d) => (d.earlyMin ? 'is-warning' : undefined) },
            { key: 'note', header: t('today.note'), cell: (d) => <DayNote day={d} /> },
            ...(onFix ? [{ key: 'fix', header: t('correction.column'), cell: (d: TimesheetDay) => (
              <FixCell day={d} onFix={() => onFix(d)} onOpen={() => d.correction && onOpenCorrection?.(d.correction.id)} />
            ) }] : []),
          ]}
        />
      </Card>
    </>
  );
}

function DayChip({ day: d }: { day: TimesheetDay }) {
  const { t } = useTranslation();
  if (d.status === 'LATE') return <Status tone="amber">{t('today.statusName.LATE')}</Status>;
  if (d.status === 'IN') return <Status tone="green">{t('timesheet.present')}</Status>;
  if (d.status === 'ON_LEAVE') return <Status tone="blue">{t('today.statusName.ON_LEAVE')}</Status>;
  if (d.status === 'OFF') return <Status tone="grey">{d.offReason === 'HOLIDAY' ? t('today.publicHoliday') : t('today.restDay')}</Status>;
  if (d.absent) return <Status tone="red">{t('today.absent')}</Status>;
  return d.overdue
    ? <Status tone="red">{t('today.overdueBy', { n: d.overdueMin })}</Status>
    : <Status tone="grey">{t('today.notYet', { time: hhmm(d.expectedIn) })}</Status>;
}

/** "09:02–12:30, 13:30–still in" or "09:02–no clock-out". */
function Sessions({ day: d }: { day: TimesheetDay }) {
  const { t } = useTranslation();
  if (d.sessions.length === 0) return <span className="muted">—</span>;
  return (
    <span className="ts-sessions">
      {d.sessions.map((s, i) => (
        <span key={i} className={s.out ? undefined : d.missedOut ? 'is-missing' : 'is-open'}>
          {hhmm(s.in)}–{s.out ? hhmm(s.out) : d.missedOut ? t('timesheet.noClockOut') : t('today.stillInShort')}
        </span>
      ))}
    </span>
  );
}

/** How far back a day can be corrected (the server's limit). */
const CORRECTABLE_DAYS = 60;

/** Waiting or sent back: the request (open it). Otherwise "Fix" (also to correct a day again), within the last 60 days. */
function FixCell({ day: d, onFix, onOpen }: { day: TimesheetDay; onFix: () => void; onOpen: () => void }) {
  const { t } = useTranslation();
  const [oldest] = useState(() => addDays(isoDate(new Date()), -CORRECTABLE_DAYS));
  const open = d.correction && (d.correction.status === 'PENDING' || d.correction.status === 'RETURNED');
  if (open) {
    return (
      <button type="button" className="link-button" onClick={onOpen} aria-label={t('correction.openLabel')}>
        <CorrectionStatusChip status={d.correction!.status} />
      </button>
    );
  }
  if (d.date < oldest) return <span className="muted">—</span>;
  return (
    <span className="ts-fix">
      <button type="button" className="btn" onClick={onFix}
        aria-label={t('correction.fixLabel', { date: formatDate(d.date, { weekday: 'long', day: 'numeric', month: 'long' }) })}>
        {t('correction.fix')}
      </button>
    </span>
  );
}

function DayNote({ day: d }: { day: TimesheetDay }) {
  const { t } = useTranslation();
  const parts = [
    d.corrected && t('correction.corrected'),
    d.overtimeMin > 0 && t('overtime.onDay', { time: duration(t, d.overtimeMin) }),
    d.holiday,
    d.leaveType && (d.leavePart === 'AM' ? t('today.leaveAm', { type: d.leaveType })
      : d.leavePart === 'PM' ? t('today.leavePm', { type: d.leaveType }) : d.leaveType),
    d.firstIn && d.offReason && (d.offReason === 'HOLIDAY' ? t('today.workedHoliday') : t('today.workedRestDay')),
    d.site,
    d.flagged && t('today.flagged'),
  ].filter(Boolean);
  return parts.length ? <span className={d.flagged ? 'today-note is-flagged' : 'today-note'}>{parts.join(' · ')}</span> : <span className="muted">—</span>;
}
