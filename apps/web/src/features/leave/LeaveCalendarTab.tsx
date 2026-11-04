import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorText, SCOPE_LABEL } from '../../lib/access';
import { formatRange, monthGrid, weekdayNames } from '../../lib/dates';
import { LEAVE_STATUS as STATUS } from '../../lib/leaveStatus';
import { Avatar, Card, List, Row, Status } from '../../components/app/kit';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { longDate, partLabel, useLeaveMonth, useMonth, type DayItem } from './leaveMonth';

const MAX_CHIPS = 3;

/** A month of leave: yours (taken, waiting, planned), your team's if you can see it, and public holidays. */
export function LeaveCalendarTab({ seesOthers, hasEmployee, onOpen, onPlan }: {
  seesOthers: boolean; hasEmployee: boolean; onOpen: (requestId: string) => void; onPlan: (iso: string) => void;
}) {
  const { t } = useTranslation();
  const month = useMonth();
  const [showTeam, setShowTeam] = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const data = useLeaveMonth(month, { team: seesOthers && showTeam, mine: hasEmployee });
  const day = selected && selected >= month.from && selected <= month.to ? selected : null;
  const items = day ? data.byDate.get(day) ?? [] : [];

  return (
    <div className="lc-layout">
      <Card title={t('cal.title')} action={<MonthNav month={month} />}>
        <div className="lc-toolbar">
          <ul className="lc-legend" aria-label={t('cal.key')}>
            <li><span className="lc-swatch lc-swatch--approved" />{t('cal.approved')}</li>
            <li><span className="lc-swatch lc-swatch--pending" />{t('cal.waiting')}</li>
            {hasEmployee && <li><span className="lc-swatch lc-swatch--draft" />{t('cal.planned')}</li>}
            <li><span className="lc-swatch lc-swatch--holiday" />{t('cal.holiday')}</li>
            <li><span className="lc-swatch lc-swatch--off" />{t('cal.offDay')}</li>
          </ul>
          {seesOthers && (
            <label className="check-line">
              <input type="checkbox" checked={showTeam} onChange={(e) => setShowTeam(e.target.checked)} />
              {t('cal.showTeam', { scope: (data.scope ? SCOPE_LABEL[data.scope] : t('scope.TEAM')).toLocaleLowerCase() })}
            </label>
          )}
        </div>
        {data.error && <p className="form-error card-pad" role="alert">{errorText(data.error)}</p>}

        <div className="lc-grid" aria-label={month.title}>
          {weekdayNames().map((d) => <span key={d} className="lc-wd" aria-hidden="true">{d}</span>)}
          {monthGrid(month.year, month.month).map((iso, i) => {
            if (!iso) return <span key={`p${i}`} className="lc-pad" aria-hidden="true" />;
            const list = data.byDate.get(iso) ?? [];
            const holiday = list.find((x) => x.kind === 'HOLIDAY');
            const leave = list.filter((x) => x.kind !== 'HOLIDAY');
            const cls = ['lc-cell', !data.isWorkDay(iso) && 'is-off', holiday && 'is-holiday', iso === month.today && 'is-today',
              iso === day && 'is-selected', iso < month.today && 'is-past'].filter(Boolean).join(' ');
            return (
              <button key={iso} type="button" className={cls} onClick={() => setSelected(iso === day ? null : iso)} aria-pressed={iso === day}
                aria-label={`${longDate(iso)}${holiday ? t('cal.dayLabelHoliday', { name: holiday.label }) : ''}${leave.length ? t('cal.dayLabelLeave', { count: leave.length }) : ''}`}>
                <span className="lc-num">{Number(iso.slice(8))}</span>
                {holiday && <span className="lc-holiday">{holiday.label}</span>}
                {leave.slice(0, MAX_CHIPS).map((x) => <Chip key={x.key} item={x} />)}
                {leave.length > MAX_CHIPS && <span className="lc-more">{t('cal.more', { n: leave.length - MAX_CHIPS })}</span>}
              </button>
            );
          })}
        </div>
      </Card>

      <Card title={day ? longDate(day) : t('cal.pickDay')}>
        {!day ? (
          <p className="hint card-pad">{t('cal.pickDayHint')}</p>
        ) : (
          <List>
            {items.map((x) => x.kind === 'HOLIDAY' ? (
              <Row key={x.key} lead={<Glyph icon="star" tone="purple" />} title={x.label} meta={t('cal.holiday')} />
            ) : (
              <Row
                key={x.key}
                lead={x.who === 'me' ? <Glyph icon="calendar" tone={x.tone} /> : <Avatar name={x.request!.employeeName} />}
                title={x.who === 'me' ? t('cal.you', { type: x.request!.typeName }) : x.request!.employeeName}
                meta={[x.who === 'team' && x.request!.typeName, formatRange(x.request!.startDate, x.request!.endDate, x.request!.dayPart),
                  partLabel(x.part)].filter(Boolean).join(' · ')}
                onClick={() => onOpen(x.request!.id)}
                trail={<Status tone={STATUS[x.kind].tone}>{STATUS[x.kind].label}</Status>}
              />
            ))}
            {items.length === 0 && <Row title={data.isWorkDay(day) ? t('cal.nobodyOff') : t('cal.offDay')} />}
          </List>
        )}
        {day && hasEmployee && day >= month.today && data.isWorkDay(day) && !items.some((x) => x.kind === 'HOLIDAY' || x.who === 'me') && (
          <div className="card-pad">
            <button type="button" className="btn small" onClick={() => onPlan(day)}>{t('cal.planFrom')}</button>
          </div>
        )}
      </Card>
    </div>
  );
}

export function Chip({ item }: { item: DayItem }) {
  const { t } = useTranslation();
  const half = item.part !== 'FULL' ? ` (${item.part === 'AM' ? t('cal.am') : t('cal.pm')})` : '';
  return (
    <span className={`lc-chip lc-chip--${item.kind.toLowerCase()} tone-${item.tone}${item.who === 'me' ? ' is-mine' : ''}`}>
      {item.label}{half}
    </span>
  );
}

export function MonthNav({ month }: { month: ReturnType<typeof useMonth> }) {
  const { t } = useTranslation();
  return (
    <div className="cal-nav">
      <button type="button" className="icon-pill" onClick={() => month.go(-1)} aria-label={t('cal.prev')}>
        <Icon name="chevron" size={16} style={{ transform: 'rotate(180deg)' }} />
      </button>
      <button type="button" className="cal-nav__title" onClick={month.reset} title={t('cal.thisMonth')} aria-live="polite">
        {month.title}
      </button>
      <button type="button" className="icon-pill" onClick={() => month.go(1)} aria-label={t('cal.next')}>
        <Icon name="chevron" size={16} />
      </button>
    </div>
  );
}
