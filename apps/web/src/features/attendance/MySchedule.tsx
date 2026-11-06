import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { AvailabilityDay, MyShift, PublishedShift, ShiftRequest, ShiftRequestKind, ShiftRequestStatus } from '@hrms/api-client';
import { api } from '../../lib/api';
import { can, errorText, useAccess } from '../../lib/access';
import { addDays, formatDate, isoDate, relative } from '../../lib/dates';
import { useUrlFilters } from '../../lib/filters';
import { Status, type StatusTone } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Icon } from '../../components/Icon';
import { hhmm, mondayOf, shiftHours } from './rosterText';
import './rosters.css';
import './schedule.css';

const minutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
const longDay = (d: string) => formatDate(d, { weekday: 'long', day: 'numeric', month: 'long' });
const shortDay = (d: string) => formatDate(d, { weekday: 'short', day: 'numeric', month: 'short' });
const shiftText = (s: { date: string; startTime: string; endTime: string; site?: string }) =>
  `${shortDay(s.date)}, ${hhmm(s.startTime)}–${hhmm(s.endTime)}${s.site ? `, ${s.site}` : ''}`;

const TONE: Record<ShiftRequestStatus, StatusTone> = {
  COLLEAGUE: 'amber', PENDING: 'amber', APPROVED: 'green', REJECTED: 'red', RETURNED: 'amber', DECLINED: 'red', CANCELLED: 'grey',
};

type Drawer = 'open' | 'requests' | 'availability';

/** "now" as local date and minutes since midnight, refreshed every minute for the now line. */
function useNow() {
  const read = () => {
    const d = new Date();
    return { date: isoDate(d), min: d.getHours() * 60 + d.getMinutes(), at: d.getTime() };
  };
  const [now, setNow] = useState(read);
  useEffect(() => {
    const id = window.setInterval(() => setNow(read()), 60_000);
    return () => window.clearInterval(id);
  }, []);
  return now;
}

/**
 * Attendance → My schedule. One sentence says when you work next; requests from colleagues that need your answer sit
 * right under it. The week is drawn as a time strip (a row per day, shifts as bars on an hour scale, a line for now);
 * click a shift for its details and to give or swap it. Open shifts, your requests and when you can work share one
 * panel below. ?week= (any day of it) picks the week, as notifications link it; ?show= opens a panel section.
 */
export function MySchedule() {
  const { t } = useTranslation();
  const filters = useUrlFilters([]);
  const now = useNow();
  const start = mondayOf(filters.param('week') ?? now.date);
  const end = addDays(start, 6);
  const week = useQuery({ queryKey: ['attendance', 'schedule', start, end], queryFn: () => api.attendance.mySchedule(start, end) });
  // The next four weeks, to find your next shift wherever the week view is.
  const ahead = useQuery({ queryKey: ['attendance', 'schedule', 'next', now.date], queryFn: () => api.attendance.mySchedule(now.date, addDays(now.date, 27)) });
  const requests = useQuery({ queryKey: ['attendance', 'shift-requests'], queryFn: api.attendance.shiftRequests.mine });
  const open = useQuery({ queryKey: ['attendance', 'open-shifts'], queryFn: api.attendance.openShifts });
  const [picked, setPicked] = useState<MyShift | null>(null);
  const forMe = (requests.data ?? []).filter((r) => r.forMe);
  const mine = (requests.data ?? []).filter((r) => r.mine);
  // Notifications about a request link with ?request=, which opens your requests.
  const drawer = (['open', 'requests', 'availability'] as const).find((x) => x === filters.param('show'))
    ?? (filters.param('request') ? 'requests' : 'open');

  const next = (ahead.data ?? []).find((s) => s.date > now.date || minutes(s.endTime) > now.min);
  const thisWeek = start === mondayOf(now.date);
  // My week, or everyone at one of your sites ("See site rosters").
  const { user } = useAccess();
  const canSeeSite = can(user, 'attendance:rosters');
  const view = canSeeSite && filters.param('view') === 'site' ? 'site' : 'mine';

  return (
    <div className="sched">
      <NextShift next={next} loading={ahead.isPending} now={now} />
      {forMe.length > 0 && <ForMe requests={forMe} />}

      <section className="sched-week" aria-labelledby="sched-week-title">
        <header className="sched-week__head">
          <h2 id="sched-week-title" className="sched-week__title">
            {formatDate(start, { day: 'numeric', month: 'short' })} – {formatDate(end, { day: 'numeric', month: 'short', year: 'numeric' })}
          </h2>
          {canSeeSite && (
            <div className="segmented sched-week__view" role="radiogroup" aria-label={t('schedule.whose')}>
              {(['mine', 'site'] as const).map((v) => (
                <label key={v} className={view === v ? 'is-on' : undefined}>
                  <input type="radio" name="sched-view" checked={view === v} onChange={() => filters.setParam('view', v === 'mine' ? null : v)} />
                  {t(`schedule.view.${v}`)}
                </label>
              ))}
            </div>
          )}
          <div className="sched-week__nav" role="group" aria-label={t('roster.week')}>
            <button type="button" className="icon-pill" onClick={() => filters.setParam('week', addDays(start, -7))} aria-label={t('roster.prevWeek')}>
              <Icon name="chevron" size={14} style={{ transform: 'rotate(180deg)' }} />
            </button>
            {!thisWeek && <button type="button" className="btn small" onClick={() => filters.setParam('week', null)}>{t('schedule.thisWeek')}</button>}
            <button type="button" className="icon-pill" onClick={() => filters.setParam('week', addDays(start, 7))} aria-label={t('roster.nextWeek')}>
              <Icon name="chevron" size={14} />
            </button>
          </div>
        </header>
        {view === 'site' ? (
          <SiteRosterView start={start} siteId={filters.param('site')} onSite={(id) => filters.setParam('site', id)} today={now.date}
            me={user?.employeeId ?? null} />
        ) : (
          <>
            {week.isError && <p className="form-error" role="alert">{errorText(week.error)}</p>}
            <TimeStrip start={start} shifts={week.data ?? []} loading={week.isPending} now={now} onPick={setPicked} />
            {week.data && <WeekTotal shifts={week.data} />}
          </>
        )}
      </section>

      <section className="sched-drawer" aria-label={t('schedule.more')}>
        <div className="sched-drawer__tabs" role="tablist" aria-label={t('schedule.more')}>
          {([
            ['open', t('schedule.openShifts'), open.data?.filter((o) => !o.requested).length],
            ['requests', t('schedule.myRequests'), mine.filter((r) => ['COLLEAGUE', 'PENDING', 'RETURNED'].includes(r.status)).length],
            ['availability', t('schedule.availability'), undefined],
          ] as [Drawer, string, number | undefined][]).map(([key, label, count]) => (
            <button key={key} type="button" role="tab" id={`sched-tab-${key}`} aria-selected={drawer === key} aria-controls="sched-drawer-panel"
              className={drawer === key ? 'is-on' : undefined} onClick={() => filters.setParams({ request: null, show: key === 'open' ? null : key })}>
              {label}{count ? <span className="ui-count">{count}</span> : null}
            </button>
          ))}
        </div>
        <div id="sched-drawer-panel" role="tabpanel" aria-labelledby={`sched-tab-${drawer}`} className="sched-drawer__panel">
          {drawer === 'open' && <OpenShifts shifts={open.data ?? []} loading={open.isPending} error={open.error} />}
          {drawer === 'requests' && <MyRequests requests={mine} loading={requests.isPending} />}
          {drawer === 'availability' && <AvailabilityEditor />}
        </div>
      </section>

      {picked && <ShiftPanel shift={picked} canChange={picked.date >= now.date} onClose={() => setPicked(null)} />}
    </div>
  );
}

// ---------- the headline ----------

/** "Next: Thursday 12 November, 14:00–22:00 at HQ" with how long until it starts (or that it's on now). */
function NextShift({ next, loading, now }: { next?: MyShift; loading: boolean; now: { date: string; min: number; at: number } }) {
  const { t } = useTranslation();
  if (loading) return <p className="sched-next sched-next--quiet">…</p>;
  if (!next) return <p className="sched-next sched-next--quiet">{t('schedule.nothingNext')}</p>;
  const startsIn = (new Date(`${next.date}T${hhmm(next.startTime)}`).getTime() - now.at) / 60_000;
  const onNow = next.date === now.date && minutes(next.startTime) <= now.min;
  const when = onNow ? t('schedule.onNow', { end: hhmm(next.endTime) })
    : startsIn < 60 ? t('schedule.inMinutes', { count: Math.max(1, Math.round(startsIn)) })
      : startsIn < 24 * 60 ? t('schedule.inHours', { count: Math.round(startsIn / 60) })
        : t('schedule.inDays', { count: Math.round(startsIn / (24 * 60)) });
  return (
    <div className="sched-next">
      <p className="sched-next__line">
        {t(onNow ? 'schedule.nowLine' : 'schedule.nextLine', {
          day: next.date === now.date ? t('schedule.today') : next.date === addDays(now.date, 1) ? t('schedule.tomorrow') : longDay(next.date),
          start: hhmm(next.startTime), end: hhmm(next.endTime), site: next.site,
        })}
      </p>
      <p className="sched-next__when">{when}{next.note && <span className="sched-next__note">{next.note}</span>}</p>
    </div>
  );
}

// ---------- the week ----------

/**
 * A row per day; each shift is a bar placed by its start and end on an hour scale that fits the week's shifts
 * (06:00–22:00 when there are none earlier or later). Today has a line at the current time.
 */
function TimeStrip({ start, shifts, loading, now, onPick }: {
  start: string; shifts: MyShift[]; loading: boolean; now: { date: string; min: number }; onPick: (s: MyShift) => void;
}) {
  const { t } = useTranslation();
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i));
  const from = Math.min(6 * 60, ...shifts.map((s) => Math.floor(minutes(s.startTime) / 60) * 60));
  const to = Math.max(22 * 60, ...shifts.map((s) => Math.ceil(minutes(s.endTime) / 60) * 60));
  const span = to - from;
  const at = (m: number) => `${((m - from) / span) * 100}%`;
  const ticks: number[] = [];
  for (let m = Math.ceil(from / 180) * 180; m <= to; m += 180) ticks.push(m);

  return (
    <div className="strip" aria-busy={loading || undefined}>
      <div className="strip__scale" aria-hidden="true">
        <span className="strip__day" />
        <span className="strip__track">
          {ticks.map((m) => <span key={m} className="strip__tick" style={{ left: at(m) }}>{String(m / 60).padStart(2, '0')}:00</span>)}
        </span>
      </div>
      <ol className="strip__days">
        {days.map((d) => {
          const mine = shifts.filter((s) => s.date === d);
          const today = d === now.date;
          return (
            <li key={d} className={['strip__row', today && 'is-today', d < now.date && 'is-past'].filter(Boolean).join(' ')}>
              <span className="strip__day">
                <span className="strip__weekday">{formatDate(d, { weekday: 'short' })}</span>
                <span className="strip__date">{formatDate(d, { day: 'numeric' })}</span>
              </span>
              <span className="strip__track">
                {ticks.map((m) => <span key={m} className="strip__grid" style={{ left: at(m) }} aria-hidden="true" />)}
                {mine.length === 0 && <span className="strip__off">{loading ? '' : t('schedule.off')}</span>}
                {mine.map((s) => (
                  <button key={s.id} type="button" className={`strip__bar shift--${s.tone}`}
                    style={{ left: at(minutes(s.startTime)), width: `calc(${at(minutes(s.endTime))} - ${at(minutes(s.startTime))})` }}
                    onClick={() => onPick(s)}
                    aria-label={t('schedule.barLabel', { day: longDay(d), start: hhmm(s.startTime), end: hhmm(s.endTime), site: s.site })}>
                    <span className="strip__time">{hhmm(s.startTime)}–{hhmm(s.endTime)}</span>
                    <span className="strip__site">{s.code ? `${s.code} ` : ''}{s.site}</span>
                  </button>
                ))}
                {today && now.min >= from && now.min <= to && (
                  <span className="strip__now" style={{ left: at(now.min) }} aria-hidden="true" />
                )}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/** "4 shifts, 30 h 30 m paid" for the week. */
function WeekTotal({ shifts }: { shifts: MyShift[] }) {
  const { t } = useTranslation();
  if (shifts.length === 0) return <p className="sched-week__total">{t('schedule.emptyWeek')}</p>;
  const paid = shifts.reduce((n, s) => n + shiftHours(s.startTime, s.endTime, s.breakMin), 0);
  return (
    <p className="sched-week__total">
      {t('schedule.weekTotal', { count: shifts.length, time: t('timesheet.hm', { h: Math.floor(paid / 60), m: String(paid % 60).padStart(2, '0') }) })}
    </p>
  );
}

/**
 * Everyone at one of your sites this week, from the published roster: a row per person (you first, highlighted), open
 * shifts on their own row. Read only; names and times, nothing personal.
 */
function SiteRosterView({ start, siteId, onSite, today, me }: {
  start: string; siteId: string | null; onSite: (id: string) => void; today: string; me: string | null;
}) {
  const { t } = useTranslation();
  const roster = useQuery({
    queryKey: ['attendance', 'site-roster', siteId, start],
    queryFn: () => api.attendance.siteRoster(start, siteId ?? undefined),
  });
  const r = roster.data;
  if (roster.isError) return <p className="form-error" role="alert">{errorText(roster.error)}</p>;
  if (!r) return <p className="sched-empty">…</p>;
  if (!r.site) return <p className="sched-empty">{t('schedule.noSites')}</p>;
  const people = [...r.people].sort((a, b) => (a.id === me ? -1 : b.id === me ? 1 : a.name.localeCompare(b.name)));
  const open = r.shifts.filter((s) => s.employeeId === null);
  const row = (id: string | null) => r.days.map((d) => {
    const list = r.shifts.filter((s) => s.employeeId === id && s.date === d);
    return (
      <td key={d} className={d === today ? 'is-today' : undefined}>
        {list.length === 0 ? <span className="muted" aria-label={t('schedule.off')}>·</span> : list.map((s) => (
          <span key={s.id} className={`shift-chip shift--${s.tone}`}>{s.code && <strong>{s.code}</strong>} {hhmm(s.startTime)}–{hhmm(s.endTime)}</span>
        ))}
      </td>
    );
  });

  return (
    <div className="site-roster">
      {r.sites.length > 1 && (
        <label className="field site-roster__site">
          <span className="sr-only">{t('roster.site')}</span>
          <select value={r.site.id} onChange={(e) => onSite(e.target.value)}>
            {r.sites.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
      )}
      {r.shifts.length === 0 ? <p className="sched-empty">{t('schedule.siteEmpty', { site: r.site.name })}</p> : (
        <div className="roster-scroll">
          <table className="roster-grid site-roster__grid">
            <caption className="sr-only">{t('roster.caption', { site: r.site.name })}</caption>
            <thead>
              <tr>
                <th scope="col">{t('roster.person')}</th>
                {r.days.map((d) => (
                  <th key={d} scope="col" className={d === today ? 'is-today' : undefined}>
                    {formatDate(d, { weekday: 'short' })} <span className="muted">{formatDate(d, { day: 'numeric' })}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {people.map((p) => (
                <tr key={p.id} className={p.id === me ? 'is-me' : undefined}>
                  <th scope="row">{p.id === me ? t('schedule.you') : p.name}</th>
                  {row(p.id)}
                </tr>
              ))}
              {open.length > 0 && (
                <tr className="roster-open">
                  <th scope="row">{t('roster.openShifts')}</th>
                  {row(null)}
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ---------- requests that need your answer ----------

function useRefresh() {
  const queryClient = useQueryClient();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: ['attendance'] });
    await queryClient.invalidateQueries({ queryKey: ['approvals'] });
  };
}

function requestTitle(t: TFunction, r: ShiftRequest) {
  return r.kind === 'CLAIM' ? t('schedule.kind.CLAIM')
    : r.kind === 'SWAP' ? t('schedule.kindWith.SWAP', { name: r.mine ? r.colleagueName : r.requesterName })
      : r.mine ? t('schedule.kindWith.GIVE', { name: r.colleagueName }) : t('schedule.givenBy', { name: r.requesterName });
}

function ForMe({ requests }: { requests: ShiftRequest[] }) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const answer = useMutation({
    mutationFn: async ({ id, yes }: { id: string; yes: boolean }) => {
      if (yes) await api.attendance.shiftRequests.accept(id);
      else await api.attendance.shiftRequests.decline(id);
    },
    onSuccess: refresh,
  });
  return (
    <section className="sched-ask" aria-label={t('schedule.forYou')}>
      {answer.error && <p className="form-error" role="alert">{errorText(answer.error)}</p>}
      {requests.map((r) => (
        <div key={r.id} className="sched-ask__item">
          <p className="sched-ask__text">
            <strong>{requestTitle(t, r)}.</strong>{' '}
            {r.shift && t('schedule.youTake', { shift: shiftText(r.shift) })}
            {r.colleagueShift && <>; {t('schedule.theyTake', { shift: shiftText(r.colleagueShift) })}</>}.
            {r.reason && <span className="muted"> “{r.reason}”</span>}
          </p>
          <span className="sched-ask__actions">
            <button type="button" className="btn small" disabled={answer.isPending} onClick={() => answer.mutate({ id: r.id, yes: false })}>{t('schedule.decline')}</button>
            <button type="button" className="btn small primary" disabled={answer.isPending} onClick={() => answer.mutate({ id: r.id, yes: true })}>{t('schedule.accept')}</button>
          </span>
        </div>
      ))}
    </section>
  );
}

// ---------- the panel below ----------

function OpenShifts({ shifts, loading, error }: { shifts: { shift: PublishedShift; requested: boolean }[]; loading: boolean; error: unknown }) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const claim = useMutation({ mutationFn: (shiftId: string) => api.attendance.shiftRequests.ask({ kind: 'CLAIM', shiftId }), onSuccess: refresh });
  return (
    <>
      {(error || claim.error) && <p className="form-error" role="alert">{errorText(error ?? claim.error)}</p>}
      {!loading && shifts.length === 0 && <p className="sched-empty">{t('schedule.noOpen')}</p>}
      <ul className="sched-list">
        {shifts.map(({ shift: s, requested }) => (
          <li key={s.id}>
            <span className={`sched-list__swatch shift--${s.tone}`} aria-hidden="true" />
            <span className="sched-list__main">
              <span className="sched-list__title">{shortDay(s.date)}, {hhmm(s.startTime)}–{hhmm(s.endTime)}</span>
              <span className="sched-list__meta">{s.code ? `${s.code}, ` : ''}{s.site}</span>
            </span>
            {requested ? <Status tone="amber">{t('schedule.claimed')}</Status> : (
              <button type="button" className="btn small primary" disabled={claim.isPending} onClick={() => claim.mutate(s.id)}>{t('schedule.claim')}</button>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}

function MyRequests({ requests, loading }: { requests: ShiftRequest[]; loading: boolean }) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const cancel = useMutation({ mutationFn: (id: string) => api.attendance.shiftRequests.cancel(id), onSuccess: refresh });
  return (
    <>
      {cancel.error && <p className="form-error" role="alert">{errorText(cancel.error)}</p>}
      {!loading && requests.length === 0 && <p className="sched-empty">{t('schedule.noRequests')}</p>}
      <ul className="sched-list">
        {requests.map((r) => (
          <li key={r.id}>
            <span className="sched-list__main">
              <span className="sched-list__title">{requestTitle(t, r)}</span>
              <span className="sched-list__meta">
                {r.shift && shiftText(r.shift)}
                {r.colleagueShift && <>, {t('schedule.forShift', { shift: shiftText(r.colleagueShift) })}</>}
              </span>
              <span className="sched-list__meta">
                {r.pendingWith ? t('correction.waitingFor', { name: r.pendingWith.approver }) : relative(r.createdAt)}
                {r.returnedComment && <> “{r.returnedComment}”</>}
              </span>
            </span>
            <Status tone={TONE[r.status]}>{t(`schedule.status.${r.status}`)}</Status>
            {(r.status === 'COLLEAGUE' || r.status === 'PENDING' || r.status === 'RETURNED') && (
              <button type="button" className="btn small" disabled={cancel.isPending} onClick={() => cancel.mutate(r.id)}>{t('correction.withdraw')}</button>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}

/** When you can work, per weekday: any time, not available, or up to three times. */
function AvailabilityEditor() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const saved = useQuery({ queryKey: ['attendance', 'availability'], queryFn: api.attendance.availability.mine });
  const [draft, setDraft] = useState<AvailabilityDay[] | null>(null);
  const days = draft ?? saved.data ?? [];
  const save = useMutation({
    mutationFn: () => api.attendance.availability.save(days.map((d) => ({ ...d, windows: d.mode === 'WINDOWS' ? d.windows : [] }))),
    onSuccess: (data) => {
      queryClient.setQueryData(['attendance', 'availability'], data);
      setDraft(null);
    },
  });
  const change = (weekday: number, next: Partial<AvailabilityDay>) => setDraft(days.map((d) => (d.weekday === weekday ? { ...d, ...next } : d)));
  const weekdayName = (n: number) => formatDate(addDays('2026-11-09', n - 1), { weekday: 'long' }); // 9 Nov 2026 is a Monday
  const valid = days.every((d) => d.mode !== 'WINDOWS' || (d.windows.length > 0 && d.windows.every((w) => w.start && w.end && w.end > w.start)));

  return (
    <form className="avail" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <p className="sched-empty">{t('schedule.availabilityHint')}</p>
      {(saved.error || save.error) && <p className="form-error" role="alert">{errorText(saved.error ?? save.error)}</p>}
      <ul className="avail__days">
        {days.map((d) => (
          <li key={d.weekday} className={`avail__day avail__day--${d.mode.toLowerCase()}`}>
            <span className="avail__name">{weekdayName(d.weekday)}</span>
            <select value={d.mode} aria-label={weekdayName(d.weekday)}
              onChange={(e) => change(d.weekday, { mode: e.target.value as AvailabilityDay['mode'],
                windows: e.target.value === 'WINDOWS' && d.windows.length === 0 ? [{ start: '09:00', end: '18:00' }] : d.windows })}>
              <option value="ANY">{t('schedule.mode.ANY')}</option>
              <option value="NONE">{t('schedule.mode.NONE')}</option>
              <option value="WINDOWS">{t('schedule.mode.WINDOWS')}</option>
            </select>
            {d.mode === 'WINDOWS' && (
              <span className="avail__windows">
                {d.windows.map((w, i) => (
                  <span key={i} className="avail__window">
                    <input type="time" value={hhmm(w.start)} aria-label={t('roster.start')}
                      onChange={(e) => change(d.weekday, { windows: d.windows.map((x, j) => (j === i ? { ...x, start: e.target.value } : x)) })} />
                    <span aria-hidden="true">–</span>
                    <input type="time" value={hhmm(w.end)} aria-label={t('roster.end')}
                      onChange={(e) => change(d.weekday, { windows: d.windows.map((x, j) => (j === i ? { ...x, end: e.target.value } : x)) })} />
                    {d.windows.length > 1 && (
                      <button type="button" className="icon-pill" aria-label={t('schedule.removeTime')}
                        onClick={() => change(d.weekday, { windows: d.windows.filter((_, j) => j !== i) })}><Icon name="close" size={12} /></button>
                    )}
                  </span>
                ))}
                {d.windows.length < 3 && (
                  <button type="button" className="btn small" onClick={() => change(d.weekday, { windows: [...d.windows, { start: '18:00', end: '22:00' }] })}>
                    <Icon name="plus" size={12} />{t('schedule.addTime')}
                  </button>
                )}
              </span>
            )}
          </li>
        ))}
      </ul>
      {draft && (
        <div className="panel-actions">
          <button type="submit" className="btn primary" disabled={!valid || save.isPending}>{t('schedule.saveAvailability')}</button>
          <button type="button" className="btn" onClick={() => setDraft(null)}>{t('common.cancel')}</button>
        </div>
      )}
    </form>
  );
}

// ---------- one shift ----------

/** A shift's details; for today or later, give it to a colleague or swap it for one of theirs. */
function ShiftPanel({ shift, canChange, onClose }: { shift: MyShift; canChange: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const refresh = useRefresh();
  const options = useQuery({
    queryKey: ['attendance', 'shift-options', shift.id],
    queryFn: () => api.attendance.shiftRequests.options(shift.id),
    enabled: canChange,
  });
  const [kind, setKind] = useState<Exclude<ShiftRequestKind, 'CLAIM'>>('GIVE');
  const [colleagueId, setColleagueId] = useState('');
  const [theirShift, setTheirShift] = useState('');
  const [reason, setReason] = useState('');
  const colleague = options.data?.colleagues.find((c) => c.id === colleagueId);
  const ask = useMutation({
    mutationFn: () => api.attendance.shiftRequests.ask({
      kind, shiftId: shift.id, colleagueId, colleagueShiftId: kind === 'SWAP' ? theirShift : null, reason: reason.trim() || null,
    }),
    onSuccess: async () => { await refresh(); onClose(); },
  });
  const valid = !!colleagueId && (kind === 'GIVE' || !!theirShift);
  const paid = shiftHours(shift.startTime, shift.endTime, shift.breakMin);

  return (
    <Panel title={longDay(shift.date)} onClose={onClose}>
      <div className="stack">
        <div className={`shift-detail shift--${shift.tone}`}>
          <p className="shift-detail__time">{hhmm(shift.startTime)}–{hhmm(shift.endTime)}</p>
          <p>{shift.code ? `${shift.code}, ` : ''}{shift.site}</p>
          <p className="muted">
            {shift.breakMin > 0 ? t('schedule.paidWithBreak', { time: t('timesheet.hm', { h: Math.floor(paid / 60), m: String(paid % 60).padStart(2, '0') }), n: shift.breakMin })
              : t('schedule.paidNoBreak', { time: t('timesheet.hm', { h: Math.floor(paid / 60), m: String(paid % 60).padStart(2, '0') }) })}
          </p>
          {shift.note && <p>{shift.note}</p>}
        </div>

        {canChange && (
          <form className="stack" onSubmit={(e) => { e.preventDefault(); ask.mutate(); }}>
            <h3 className="panel-subhead">{t('schedule.giveOrSwap')}</h3>
            {(options.error || ask.error) && <p className="form-error" role="alert">{errorText(options.error ?? ask.error)}</p>}
            <div className="segmented" role="radiogroup" aria-label={t('schedule.whatToDo')}>
              {(['GIVE', 'SWAP'] as const).map((k) => (
                <label key={k} className={kind === k ? 'is-on' : undefined}>
                  <input type="radio" name="offer-kind" checked={kind === k} onChange={() => setKind(k)} />{t(`schedule.kind.${k}`)}
                </label>
              ))}
            </div>
            <label className="field">
              {t('schedule.colleague')}
              <select value={colleagueId} onChange={(e) => { setColleagueId(e.target.value); setTheirShift(''); }} required>
                <option value="">{options.isPending ? '…' : t('schedule.chooseColleague')}</option>
                {(options.data?.colleagues ?? []).map((c) => <option key={c.id} value={c.id}>{c.fullName}</option>)}
              </select>
            </label>
            {kind === 'SWAP' && colleague && (
              colleague.shifts.length === 0 ? <p className="hint">{t('schedule.noTheirShifts', { name: colleague.fullName })}</p> : (
                <label className="field">
                  {t('schedule.theirShift')}
                  <select value={theirShift} onChange={(e) => setTheirShift(e.target.value)} required>
                    <option value="">{t('schedule.chooseShift')}</option>
                    {colleague.shifts.map((s) => <option key={s.id} value={s.id}>{shiftText(s)}</option>)}
                  </select>
                </label>
              )
            )}
            <label className="field">
              {t('overtime.reason')}
              <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} placeholder={t('schedule.reasonHint')} />
            </label>
            <p className="hint">{t('schedule.offerHint')}</p>
            <div className="panel-actions">
              <button type="submit" className="btn primary" disabled={!valid || ask.isPending}>{t(kind === 'GIVE' ? 'schedule.sendGive' : 'schedule.sendSwap')}</button>
            </div>
          </form>
        )}
      </div>
    </Panel>
  );
}
