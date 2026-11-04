import { useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { Icon, type IconName } from '../Icon';
import { Glyph, type Tone } from '../Glyph';
import { usePageTitle, useSectionTitle } from '../../lib/title';

/*
 * In-app building blocks, following the design references: white hairline cards on a grey canvas,
 * "Title (count) · See all" card headers, list rows with a coloured glyph, stat cards with an icon chip.
 */

export function PageHeader({ eyebrow, title, subtitle, actions, docTitle }: {
  eyebrow?: ReactNode; title: ReactNode; subtitle?: ReactNode; actions?: ReactNode;
  /** The browser tab title, when the heading isn't plain text or isn't the page's name (e.g. a greeting). */
  docTitle?: string;
}) {
  usePageTitle(docTitle ?? (typeof title === 'string' ? title : null));
  return (
    <header className="page-header">
      <div className="page-header__text">
        {eyebrow && <p className="page-header__eyebrow">{eyebrow}</p>}
        <h1 className="page-header__title">{title}</h1>
        {subtitle && <div className="page-header__subtitle">{subtitle}</div>}
      </div>
      {actions && <div className="page-header__actions">{actions}</div>}
    </header>
  );
}

export function Card({ title, count, action, children, className, padded = false, id }: {
  title?: ReactNode; count?: number; action?: ReactNode; children: ReactNode; className?: string; padded?: boolean; id?: string;
}) {
  const headingId = useId();
  return (
    <section className={['ui-card', padded && 'ui-card--padded', className].filter(Boolean).join(' ')} aria-labelledby={title ? headingId : undefined} id={id}>
      {(title || action) && (
        <header className="ui-card__head">
          {title && (
            <h2 id={headingId} className="ui-card__title">
              {title}
              {count !== undefined && <span className="ui-count">{count}</span>}
            </h2>
          )}
          {action && <div className="ui-card__action">{action}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function CardLink({ to, children }: { to: string; children: ReactNode }) {
  return <Link className="ui-card__link" to={to}>{children}</Link>;
}

export function List({ children }: { children: ReactNode }) {
  return <ul className="ui-list">{children}</ul>;
}

/** One list row: a leading glyph or avatar, a title with a meta line, and trailing content. */
export function Row({ lead, title, meta, trail, to, onClick }: {
  lead?: ReactNode; title: ReactNode; meta?: ReactNode; trail?: ReactNode; to?: string; onClick?: () => void;
}) {
  const body = (
    <>
      {lead && <span className="ui-row__lead">{lead}</span>}
      <span className="ui-row__text">
        <span className="ui-row__title">{title}</span>
        {meta && <span className="ui-row__meta">{meta}</span>}
      </span>
      {trail && <span className="ui-row__trail">{trail}</span>}
      {(to || onClick) && !trail && <Icon name="chevron" size={16} className="ui-row__chevron" />}
    </>
  );
  if (to) return <li><Link className="ui-row ui-row--link" to={to}>{body}</Link></li>;
  if (onClick) return <li><button type="button" className="ui-row ui-row--link" onClick={onClick}>{body}</button></li>;
  return <li className="ui-row">{body}</li>;
}

const AVATAR_TONES = ['#C7D2FE', '#BBF7D0', '#FED7AA', '#FBCFE8', '#BAE6FD', '#DDD6FE', '#FDE68A'];

function initials(name: string | null | undefined) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

/** Initials on a soft colour picked from the name, so the same person always gets the same colour. */
export function Avatar({ name, size = 32 }: { name: string | null | undefined; size?: number }) {
  const seed = [...(name ?? '')].reduce((n, c) => n + c.charCodeAt(0), 0);
  return (
    <span className="ui-avatar" aria-hidden="true" style={{ width: size, height: size, fontSize: size * 0.36, background: AVATAR_TONES[seed % AVATAR_TONES.length] }}>
      {initials(name)}
    </span>
  );
}

export function StatCard({ icon, label, value, note, suffix }: {
  icon: IconName; label: string; value: ReactNode; note?: ReactNode; suffix?: ReactNode;
}) {
  return (
    <div className="ui-stat">
      <span className="ui-stat__label"><span className="ui-stat__icon"><Icon name={icon} size={16} /></span>{label}</span>
      <span className="ui-stat__value">{value}{suffix && <small>{suffix}</small>}</span>
      {note && <span className="ui-stat__note">{note}</span>}
    </div>
  );
}

type StatusTone = 'blue' | 'green' | 'amber' | 'red' | 'grey';

/** Status with a dot and a word, never colour alone. */
export function Status({ tone, children }: { tone: StatusTone; children: ReactNode }) {
  return <span className={`ui-status ui-status--${tone}`}><span className="ui-status__dot" aria-hidden="true" />{children}</span>;
}

/** Small square tile: glyph, name and a status, like the agent tiles in the references. */
export function Tile({ icon, tone, name, status, onClick, pressed }: {
  icon: IconName; tone: Tone; name: string; status: ReactNode; onClick?: () => void; pressed?: boolean;
}) {
  const inner = (
    <>
      <Glyph icon={icon} tone={tone} size="lg" />
      <span className="ui-tile__name">{name}</span>
      {status}
    </>
  );
  if (onClick) {
    return <button type="button" className="ui-tile ui-tile--button" onClick={onClick} aria-pressed={pressed}>{inner}</button>;
  }
  return <div className="ui-tile">{inner}</div>;
}

export function EmptyState({ icon, tone = 'blue', title, children, action }: {
  icon: IconName; tone?: Tone; title: string; children?: ReactNode; action?: ReactNode;
}) {
  return (
    <div className="ui-empty">
      <Glyph icon={icon} tone={tone} size="lg" />
      <h2>{title}</h2>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export type MenuItem = { label: string; icon?: IconName; onSelect?: () => void; to?: string; hint?: string } | 'divider';

/** Button that opens a small list of actions. Closes on Escape, outside click or choosing an item. */
export function Menu({ trigger, label, items, align = 'start', className }: {
  trigger: ReactNode; label: string; items: MenuItem[]; align?: 'start' | 'end' | 'up'; className?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const id = useId();

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    ref.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className={['ui-menu', className].filter(Boolean).join(' ')} ref={ref}>
      <button type="button" className="ui-menu__trigger" aria-haspopup="menu" aria-expanded={open} aria-controls={id} aria-label={label} onClick={() => setOpen((v) => !v)}>
        {trigger}
      </button>
      {open && (
        <div id={id} role="menu" className={`ui-menu__list ui-menu__list--${align}`}>
          {items.map((item, i) =>
            item === 'divider' ? (
              <hr key={`d${i}`} />
            ) : item.to ? (
              <Link key={item.label} role="menuitem" to={item.to} className="ui-menu__item" onClick={() => setOpen(false)}>
                {item.icon && <Icon name={item.icon} size={16} />}<span>{item.label}</span>{item.hint && <small>{item.hint}</small>}
              </Link>
            ) : (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                className="ui-menu__item"
                onClick={() => {
                  setOpen(false);
                  item.onSelect?.();
                }}
              >
                {item.icon && <Icon name={item.icon} size={16} />}<span>{item.label}</span>{item.hint && <small>{item.hint}</small>}
              </button>
            ),
          )}
        </div>
      )}
    </div>
  );
}

export type TabItem = { key: string; label: string; icon?: IconName; count?: number };

/**
 * Tabs for switching sections of a page. Arrow keys move between tabs (roving tab index); the panel
 * is labelled by its tab. The caller owns the selected key (e.g. kept in the URL).
 */
export function Tabs({ tabs, value, onChange, label, children, titled = false }: {
  tabs: TabItem[]; value: string; onChange: (key: string) => void; label: string; children: ReactNode;
  /** Page-level tabs: show the open tab in the browser tab title. */
  titled?: boolean;
}) {
  useSectionTitle(titled ? tabs.find((t) => t.key === value)?.label : null);
  const base = useId();
  const onKey = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const i = tabs.findIndex((t) => t.key === value);
    const next = e.key === 'ArrowRight' ? (i + 1) % tabs.length : e.key === 'ArrowLeft' ? (i - 1 + tabs.length) % tabs.length
      : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : -1;
    if (next < 0) return;
    e.preventDefault();
    onChange(tabs[next].key);
    document.getElementById(`${base}-tab-${tabs[next].key}`)?.focus();
  };
  return (
    <>
      <div className="ui-tabs" role="tablist" aria-label={label} onKeyDown={onKey}>
        {tabs.map((t) => (
          <button
            key={t.key}
            id={`${base}-tab-${t.key}`}
            type="button"
            role="tab"
            aria-selected={t.key === value}
            aria-controls={`${base}-panel`}
            tabIndex={t.key === value ? 0 : -1}
            className={t.key === value ? 'ui-tab is-active' : 'ui-tab'}
            onClick={() => onChange(t.key)}
          >
            {t.icon && <Icon name={t.icon} size={16} />}
            {t.label}
            {t.count !== undefined && <span className="ui-count">{t.count}</span>}
          </button>
        ))}
      </div>
      <div id={`${base}-panel`} role="tabpanel" aria-labelledby={`${base}-tab-${value}`} className="ui-tabpanel">
        {children}
      </div>
    </>
  );
}

/** On/off switch. A real checkbox underneath (role="switch"), so keyboard and screen readers work. */
export function Toggle({ checked, onChange, label, hint, disabled }: {
  checked: boolean; onChange: (checked: boolean) => void; label: string; hint?: string; disabled?: boolean;
}) {
  return (
    <label className={disabled ? 'ui-toggle is-disabled' : 'ui-toggle'}>
      <input type="checkbox" role="switch" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="ui-toggle__track" aria-hidden="true"><span className="ui-toggle__thumb" /></span>
      <span className="ui-toggle__text">
        <span>{label}</span>
        {hint && <small>{hint}</small>}
      </span>
    </label>
  );
}
