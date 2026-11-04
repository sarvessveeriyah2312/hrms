import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatDate } from '../../lib/dates';
import { Icon, type IconName } from '../../components/Icon';
import { Glyph, type Tone } from '../../components/Glyph';
import { Button } from '../../components/Button';

type Request = { id: 'leave' | 'claim' | 'fix'; who: string; icon: IconName; tone: Tone };
type Decision = 'approved' | 'rejected';

const REQUESTS: Request[] = [
  { id: 'leave', who: 'Wei Ling Tan', icon: 'calendar', tone: 'teal' },
  { id: 'claim', who: 'Arjun Nair', icon: 'receipt', tone: 'orange' },
  { id: 'fix', who: 'Daniel Lim', icon: 'clock', tone: 'blue' },
];

const OUT_TODAY = [
  { key: 'priya' as const, name: 'Priya Raman', icon: 'calendar' as const, tone: 'teal' as const },
  { key: 'hafiz' as const, name: 'Hafiz Rahman', icon: 'calendar' as const, tone: 'teal' as const },
  { key: 'grace' as const, name: 'Grace Wong', icon: 'home' as const, tone: 'purple' as const },
  { key: 'marcus' as const, name: 'Marcus Lee', icon: 'clock' as const, tone: 'pink' as const },
];

const SIDEBAR: { key: 'home' | 'people' | 'attendance' | 'leave' | 'claims' | 'approvals' | 'reports'; icon: IconName; current?: boolean; badge?: boolean }[] = [
  { key: 'home', icon: 'home', current: true },
  { key: 'people', icon: 'users' },
  { key: 'attendance', icon: 'clock' },
  { key: 'leave', icon: 'calendar' },
  { key: 'claims', icon: 'receipt' },
  { key: 'approvals', icon: 'inbox', badge: true },
  { key: 'reports', icon: 'chart' },
];

function greetingKey(now: Date) {
  const h = now.getHours();
  return h < 12 ? 'preview.morning' : h < 18 ? 'preview.afternoon' : 'preview.evening';
}

/**
 * The manager's home screen, rendered live so visitors can try the core job: approving a request.
 * Sample people and numbers only; nothing here talks to the API.
 */
export function HeroPreview() {
  const { t } = useTranslation();
  const [decisions, setDecisions] = useState<Record<string, Decision>>({});
  const waiting = REQUESTS.filter((r) => !decisions[r.id]).length;
  const [now] = useState(() => new Date());
  const today = formatDate(now, { weekday: 'long', day: 'numeric', month: 'long' });

  const decide = (id: string, d: Decision) => setDecisions((prev) => ({ ...prev, [id]: d }));

  return (
    <div className="hp" role="group" aria-label={t('preview.label')}>
      <aside className="hp-side" aria-hidden="true">
        <div className="hp-side__org">
          <span className="hp-side__org-mark">A</span>
          <span>Acme Sdn Bhd</span>
        </div>
        <ul>
          {SIDEBAR.map((item) => (
            <li key={item.key} className={item.current ? 'is-current' : undefined}>
              <Icon name={item.icon} size={16} />
              <span>{item.key === 'home' ? t('preview.home') : t(`nav.${item.key}`)}</span>
              {item.badge && waiting > 0 && <span className="hp-side__count">{waiting}</span>}
            </li>
          ))}
        </ul>
        <div className="hp-side__me">
          <span className="hp-avatar" style={{ background: '#C7D2FE' }}>AS</span>
          <span>
            <strong>Aisyah Salleh</strong>
            <small>{t('preview.role')}</small>
          </span>
        </div>
      </aside>

      <div className="hp-main">
        <div className="hp-bar" aria-hidden="true">
          <span className="hp-bar__crumb"><Icon name="home" size={15} /> {t('preview.home')} <Icon name="star" size={14} /></span>
          <span className="hp-bar__search"><Icon name="search" size={14} /> {t('preview.search')} <kbd>⌘K</kbd></span>
        </div>

        <div className="hp-body">
          <div className="hp-hello">
            <p className="hp-hello__date">{today}</p>
            <p className="hp-hello__title">{t('preview.hello', { greeting: t(greetingKey(now)) })}</p>
            <p className="hp-hello__sub" aria-live="polite">
              {waiting === 0 ? t('preview.caughtUp') : t('preview.needOk', { count: waiting })}
            </p>
          </div>

          <div className="hp-stats">
            <div className="hp-stat">
              <span className="hp-stat__label"><span className="hp-stat__icon"><Icon name="clock" size={14} /></span>{t('preview.clockedIn')}</span>
              <span className="hp-stat__value">41<small>{t('preview.of44')}</small></span>
              <span className="hp-stat__note">{t('preview.lateNote')}</span>
            </div>
            <div className="hp-stat">
              <span className="hp-stat__label"><span className="hp-stat__icon"><Icon name="calendar" size={14} /></span>{t('preview.outToday')}</span>
              <span className="hp-stat__value">3</span>
              <span className="hp-stat__note">{t('preview.outNote')}</span>
            </div>
            <div className="hp-stat">
              <span className="hp-stat__label"><span className="hp-stat__icon"><Icon name="shield" size={14} /></span>{t('preview.waiting')}</span>
              <span className="hp-stat__value">{waiting}</span>
              <span className="hp-stat__note">{waiting === 0 ? t('preview.nothingLeft') : t('preview.oldest')}</span>
            </div>
          </div>

          <div className="hp-cols">
            <section className="hp-panel" aria-labelledby="hp-approvals">
              <header className="hp-panel__head">
                <h3 id="hp-approvals">{t('preview.needsApproval')} <span className="hp-pill">{waiting}</span></h3>
                <button type="button" className="hp-link" onClick={() => setDecisions({})} disabled={Object.keys(decisions).length === 0}>
                  {t('preview.reset')}
                </button>
              </header>
              <ul className="hp-list">
                {REQUESTS.map((r) => {
                  const d = decisions[r.id];
                  const title = t(`preview.requests.${r.id}.title`);
                  return (
                    <li key={r.id} className={d ? `hp-row is-${d}` : 'hp-row'}>
                      <Glyph icon={r.icon} tone={r.tone} />
                      <div className="hp-row__text">
                        <span className="hp-row__title">{title}</span>
                        <span className="hp-row__meta">{r.who} · {t(`preview.requests.${r.id}.meta`)}</span>
                      </div>
                      <div className="hp-row__actions">
                        {d ? (
                          <span className={`hp-status hp-status--${d}`} role="status">
                            <Icon name={d === 'approved' ? 'check' : 'close'} size={14} strokeWidth={2.5} />
                            {d === 'approved' ? t('preview.approved') : t('preview.rejected')}
                          </span>
                        ) : (
                          <>
                            <Button size="sm" onClick={() => decide(r.id, 'rejected')} aria-label={t('preview.rejectLabel', { title })}>{t('preview.reject')}</Button>
                            <Button size="sm" variant="primary" onClick={() => decide(r.id, 'approved')} aria-label={t('preview.approveLabel', { title })}>{t('preview.approve')}</Button>
                          </>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            </section>

            <section className="hp-panel hp-panel--side" aria-labelledby="hp-out">
              <header className="hp-panel__head">
                <h3 id="hp-out">{t('preview.notAtDesk')}</h3>
              </header>
              <ul className="hp-list">
                {OUT_TODAY.map((p) => (
                  <li key={p.name} className="hp-row hp-row--compact">
                    <Glyph icon={p.icon} tone={p.tone} />
                    <div className="hp-row__text">
                      <span className="hp-row__title">{p.name}</span>
                      <span className="hp-row__meta">{t(`preview.out.${p.key}.why`)} · {t(`preview.out.${p.key}.back`)}</span>
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          </div>
        </div>
      </div>
    </div>
  );
}
