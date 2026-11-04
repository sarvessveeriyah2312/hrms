import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { DayPart, Holiday, LeaveRequest } from '@hrms/api-client';
import { api } from '../../lib/api';
import { addDays, formatDate, isoDate, isoWeekday, toTone } from '../../lib/dates';
import i18n from '../../i18n';
import type { Tone } from '../../components/Glyph';

export const longDate = (iso: string) => formatDate(iso, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

/** One thing on a calendar day: some leave (yours or a teammate's), a plan, or a public holiday. */
export interface DayItem {
  key: string;
  kind: 'APPROVED' | 'PENDING' | 'DRAFT' | 'HOLIDAY';
  who: 'me' | 'team' | 'holiday';
  label: string;
  tone: Tone;
  part: DayPart;
  request: LeaveRequest | null;
  holiday: Holiday | null;
}

/** The month being shown, with paging. */
export function useMonth() {
  const [now] = useState(() => new Date());
  const [cursor, setCursor] = useState(() => ({ year: now.getFullYear(), month: now.getMonth() }));
  const from = isoDate(new Date(cursor.year, cursor.month, 1));
  const to = isoDate(new Date(cursor.year, cursor.month + 1, 0));
  return {
    ...cursor,
    from,
    to,
    today: isoDate(now),
    title: formatDate(new Date(cursor.year, cursor.month, 1), { month: 'long', year: 'numeric' }),
    go: (delta: number) => {
      const i = cursor.year * 12 + cursor.month + delta;
      setCursor({ year: Math.floor(i / 12), month: ((i % 12) + 12) % 12 });
    },
    show: (iso: string) => setCursor({ year: Number(iso.slice(0, 4)), month: Number(iso.slice(5, 7)) - 1 }),
    reset: () => setCursor({ year: now.getFullYear(), month: now.getMonth() }),
  };
}

/**
 * Everything to draw on a month: your leave, your plans, teammates' leave (when asked for), public holidays
 * and your working week. Leave only shows on the days it counts (working days that aren't holidays).
 */
export function useLeaveMonth(month: ReturnType<typeof useMonth>, { team, mine = true }: { team: boolean; mine?: boolean }) {
  const { year, from, to, today } = month;
  const myLeave = useQuery({ queryKey: ['leave', 'mine', year], queryFn: () => api.leave.mine(year), enabled: mine });
  const drafts = useQuery({ queryKey: ['leave', 'drafts'], queryFn: api.leave.drafts.list, enabled: mine });
  const others = useQuery({
    queryKey: ['leave', 'calendar', from, to],
    queryFn: () => api.leave.calendar(from, to, true),
    enabled: team,
  });
  const holidays = useQuery({ queryKey: ['leave', 'holidays', year], queryFn: () => api.leave.holidays(year) });
  // The preview tells us the signed-in person's working week.
  const week = useQuery({ queryKey: ['leave', 'my-week'], queryFn: () => api.leave.preview(today, today, 'FULL'), staleTime: 300_000 });
  const workDays = useMemo(() => new Set(week.data?.workDays ?? [1, 2, 3, 4, 5]), [week.data]);

  const byDate = useMemo(() => {
    const map = new Map<string, DayItem[]>();
    const push = (iso: string, item: DayItem) => {
      const list = map.get(iso);
      if (list) list.push(item);
      else map.set(iso, [item]);
    };
    const holidayDates = new Set<string>();
    for (const h of holidays.data ?? []) {
      if (h.date < from || h.date > to) continue;
      holidayDates.add(h.date);
      push(h.date, { key: `h${h.id}`, kind: 'HOLIDAY', who: 'holiday', label: h.name, tone: 'purple', part: 'FULL', request: null, holiday: h });
    }
    const spread = (r: LeaveRequest, who: DayItem['who']) => {
      const kind = r.status === 'APPROVED' || r.status === 'PENDING' || r.status === 'DRAFT' ? r.status : null;
      if (!kind) return;
      for (let d = r.startDate < from ? from : r.startDate; d <= r.endDate && d <= to; d = addDays(d, 1)) {
        if (!workDays.has(isoWeekday(d)) || holidayDates.has(d)) continue;
        push(d, {
          key: `${r.id}-${d}`, kind, who, label: who === 'me' ? r.typeName : r.employeeName.split(' ')[0],
          tone: toTone(r.tone), part: r.dayPart, request: r, holiday: null,
        });
      }
    };
    if (mine) {
      for (const r of myLeave.data ?? []) spread(r, 'me');
      for (const r of drafts.data ?? []) spread(r, 'me');
    }
    if (team) for (const r of others.data?.requests ?? []) spread(r, 'team');
    return map;
  }, [holidays.data, myLeave.data, drafts.data, others.data, workDays, from, to, mine, team]);

  return {
    byDate,
    workDays,
    drafts: drafts.data ?? [],
    scope: others.data?.scope,
    error: myLeave.error ?? drafts.error ?? others.error ?? holidays.error,
    isWorkDay: (iso: string) => workDays.has(isoWeekday(iso)),
  };
}

export function partLabel(part: DayPart) {
  return part === 'AM' ? i18n.t('dates.morning') : part === 'PM' ? i18n.t('dates.afternoon') : null;
}
