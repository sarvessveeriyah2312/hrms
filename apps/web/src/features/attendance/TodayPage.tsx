import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { DayStatus, TodayPerson } from '@hrms/api-client';
import { api } from '../../lib/api';
import { can, errorText, SCOPE_LABEL, useAccess } from '../../lib/access';
import { formatDate, isoDate } from '../../lib/dates';
import { useUrlFilters, type FilterDef } from '../../lib/filters';
import { PAGE_SIZES } from '../../lib/paging';
import { Card, PageHeader, StatCard, Status } from '../../components/app/kit';
import { FilterBar } from '../../components/app/FilterBar';
import { Table } from '../../components/app/Table';
import { PlaceholderPage } from '../../app/PlaceholderPage';
import './today.css';

const STATUSES: DayStatus[] = ['IN', 'LATE', 'NOT_IN', 'ON_LEAVE', 'OFF'];
const NO_SITE = 'none';
/** Who needs attention first: overdue, late, not in yet, then everyone else. */
const RANK: Record<DayStatus, number> = { LATE: 1, NOT_IN: 2, IN: 3, ON_LEAVE: 4, OFF: 5 };
const rank = (p: TodayPerson) => (p.overdue ? 0 : RANK[p.status]);
const hhmm = (time: string | null) => (time ? time.slice(0, 5) : '—');

/** /app/attendance: Today for people who can see attendance; the rest (clock-in only) aren't built yet on the web. */
export function AttendancePage() {
  const { user } = useAccess();
  return can(user, 'attendance:view') ? <TodayPage /> : <PlaceholderPage module="attendance" />;
}

/**
 * Attendance → Today (managers): who's in, late, not in yet, on leave or off, by site, within the attendance:view
 * scope. Refreshes every minute; an earlier day can be picked. Filters are in the URL.
 */
export function TodayPage() {
  const { t } = useTranslation();
  const [todayIso] = useState(() => isoDate(new Date()));
  const defs: FilterDef[] = [
    { key: 'q', label: t('common.search'), kind: 'search', pinned: true, placeholder: t('today.search') },
    { key: 'site', label: t('today.site'), kind: 'select', pinned: true, allLabel: t('today.allSites') },
    { key: 'status', label: t('today.status'), kind: 'select', pinned: true, allLabel: t('today.anyStatus'),
      options: STATUSES.map((s) => ({ value: s, label: t(`today.statusName.${s}`) })) },
  ];
  const filters = useUrlFilters(defs);
  const date = filters.param('date') ?? '';
  const size = Number(filters.param('size'));
  const pageSize = PAGE_SIZES.includes(size) ? size : 50;

  const today = useQuery({
    queryKey: ['attendance', 'today', date],
    queryFn: () => api.attendance.today(date || undefined),
    refetchInterval: date ? false : 60_000,
  });
  const data = today.data;
  const people = useMemo(() => data?.people ?? [], [data]);

  // The site choices come from the people in scope, so they're added once the day has loaded.
  const siteOptions = [
    ...(data?.sites ?? []).map((s) => ({ value: s.id, label: s.name })),
    ...(people.some((p) => !p.siteId) ? [{ value: NO_SITE, label: t('today.noSite') }] : []),
  ];
  const bar = { ...filters, defs: filters.defs.map((d) => (d.key === 'site' ? { ...d, options: siteOptions } : d)) };

  const words = filters.values.q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const site = filters.values.site;
  const status = filters.values.status as DayStatus | '';
  const atSite = people.filter((p) => !site || (site === NO_SITE ? !p.siteId : p.siteId === site));
  const shown = atSite
    .filter((p) => !status || p.status === status)
    .filter((p) => {
      const text = [p.fullName, p.employeeNo, p.department, p.site].filter(Boolean).join(' ').toLowerCase();
      return words.every((w) => text.includes(w));
    })
    .sort((a, b) => (a.site ?? '￿').localeCompare(b.site ?? '￿') || rank(a) - rank(b) || a.fullName.localeCompare(b.fullName));

  const count = (s: DayStatus) => atSite.filter((p) => p.status === s).length;
  const overdue = atSite.filter((p) => p.overdue).length;
  const stillIn = atSite.filter((p) => p.stillIn).length;
  const label = data
    ? formatDate(data.date, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
    : '';

  return (
    <>
      <PageHeader
        title={data && !data.isToday ? t('today.titleOn', { date: label }) : t('today.title')}
        subtitle={data ? [data.isToday ? label : null, data.holiday && t('today.holiday', { name: data.holiday })].filter(Boolean).join(' · ') || t('today.subtitle') : t('today.subtitle')}
        actions={
          <div className="today-date">
            <label className="field today-date__field">
              <span className="sr-only">{t('today.pickDay')}</span>
              <input type="date" className="input" value={date || todayIso} max={todayIso}
                onChange={(e) => filters.setParam('date', !e.target.value || e.target.value === todayIso ? '' : e.target.value)} />
            </label>
            {date && <button type="button" className="btn" onClick={() => filters.setParam('date', '')}>{t('today.backToToday')}</button>}
          </div>
        }
      />
      {today.isError && <p className="form-error" role="alert">{errorText(today.error)}</p>}
      {data?.truncated && <p className="notice" role="status">{t('today.truncated', { n: data.people.length })}</p>}

      <div className="grid grid--stats">
        <StatCard icon="check" label={t('today.statusName.IN')} value={data ? count('IN') + count('LATE') : '–'}
          note={data && (data.isToday ? t('today.stillIn', { count: stillIn }) : t('today.nPeople', { count: atSite.length }))} />
        <StatCard icon="clock" label={t('today.statusName.LATE')} value={data ? count('LATE') : '–'} note={t('today.lateNote')} />
        <StatCard icon="users" label={data && !data.isToday ? t('today.absent') : t('today.statusName.NOT_IN')} value={data ? count('NOT_IN') : '–'}
          note={data && (overdue > 0 ? t('today.nOverdue', { count: overdue }) : t('today.noneOverdue'))} />
        <StatCard icon="calendar" label={t('today.statusName.ON_LEAVE')} value={data ? count('ON_LEAVE') : '–'}
          note={data && t('today.offNote', { count: count('OFF') })} />
      </div>

      <Card title={t('today.everyone')} count={data ? shown.length : undefined}
        action={data && <Status tone="blue">{SCOPE_LABEL[data.scope]}</Status>}>
        <div className="card-pad">
          <FilterBar filters={bar} label={t('today.filters')} />
        </div>
        <Table
          label={t('today.everyone')}
          rows={shown}
          rowKey={(p) => p.employeeId}
          loading={today.isPending}
          empty={people.length === 0 ? t('today.nobody') : t('today.noMatch')}
          resetKey={`${date}-${site}-${status}-${words.join(' ')}`}
          pageSize={pageSize}
          onPageSizeChange={(n) => filters.setParam('size', n === 50 ? '' : String(n))}
          groupOf={(p) => ({ key: p.siteId ?? NO_SITE, label: groupLabel(p, data?.sites ?? [], t) })}
          columns={[
            { key: 'name', header: t('today.person'), rowHeader: true, cell: (p) => (
              <span className="today-person">
                <span>{p.fullName}</span>
                <span className="muted">{[p.employeeNo, p.department].filter(Boolean).join(' · ')}</span>
              </span>
            ) },
            { key: 'status', header: t('today.status'), cell: (p) => <DayChip person={p} isToday={!!data?.isToday} /> },
            { key: 'expected', header: t('today.expected'), align: 'end', cell: (p) => hhmm(p.expectedAt) },
            { key: 'in', header: t('today.in'), align: 'end', cell: (p) => hhmm(p.firstIn) },
            { key: 'out', header: t('today.out'), align: 'end', cell: (p) => (p.lastOut ? hhmm(p.lastOut) : p.stillIn ? <span className="muted">{t('today.stillInShort')}</span> : '—') },
            { key: 'note', header: t('today.note'), cell: (p) => <Note person={p} /> },
          ]}
        />
      </Card>
    </>
  );
}

function groupLabel(p: TodayPerson, sites: { id: string; name: string; startTime: string; endTime: string }[], t: TFunction) {
  const s = sites.find((x) => x.id === p.siteId);
  if (!s) return t('today.noSite');
  return `${s.name} · ${t('today.hours', { start: hhmm(s.startTime), end: hhmm(s.endTime) })}`;
}

function DayChip({ person: p, isToday }: { person: TodayPerson; isToday: boolean }) {
  const { t } = useTranslation();
  switch (p.status) {
    case 'IN':
      return <Status tone="green">{t('today.statusName.IN')}</Status>;
    case 'LATE':
      return <Status tone="amber">{t('today.lateBy', { n: p.minutesLate })}</Status>;
    case 'NOT_IN':
      if (!p.overdue) return <Status tone="grey">{t('today.notYet', { time: hhmm(p.expectedAt) })}</Status>;
      return <Status tone="red">{isToday ? t('today.overdueBy', { n: p.minutesLate }) : t('today.absent')}</Status>;
    case 'ON_LEAVE':
      return <Status tone="blue">{t('today.statusName.ON_LEAVE')}</Status>;
    case 'OFF':
      return <Status tone="grey">{p.offReason === 'HOLIDAY' ? t('today.publicHoliday') : t('today.restDay')}</Status>;
  }
}

function Note({ person: p }: { person: TodayPerson }) {
  const { t } = useTranslation();
  const parts = [
    p.leaveType && (p.leavePart === 'AM' ? t('today.leaveAm', { type: p.leaveType })
      : p.leavePart === 'PM' ? t('today.leavePm', { type: p.leaveType }) : p.leaveType),
    p.status === 'IN' && p.offReason && (p.offReason === 'HOLIDAY' ? t('today.workedHoliday') : t('today.workedRestDay')),
    p.flagged && t('today.flagged'),
  ].filter(Boolean);
  return parts.length ? <span className={p.flagged ? 'today-note is-flagged' : 'today-note'}>{parts.join(' · ')}</span> : <span className="muted">—</span>;
}
