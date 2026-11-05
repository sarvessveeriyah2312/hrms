import { useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ApiError, type SearchHit, type SearchKind } from '@hrms/api-client';
import i18n from '../../i18n';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { formatDate, formatRange } from '../../lib/dates';
import { LEAVE_STATUS } from '../../lib/leaveStatus';
import { formatMoney } from '../../lib/money';
import { Glyph, type Tone } from '../Glyph';
import { Icon, type IconName } from '../Icon';
import { Avatar, Status } from './kit';

/** A page or action the person can go to, offered before (and alongside) data results. */
export type SearchPage = { label: string; icon: IconName; to: string; hint?: string; keywords?: string; action?: boolean };

const KIND: Record<SearchKind, { icon: IconName; tone: Tone }> = {
  PERSON: { icon: 'users', tone: 'blue' }, USER: { icon: 'users', tone: 'blue' },
  LEAVE: { icon: 'calendar', tone: 'teal' }, LEAVE_TYPE: { icon: 'calendar', tone: 'teal' }, HOLIDAY: { icon: 'star', tone: 'purple' },
  CLAIM: { icon: 'receipt', tone: 'orange' }, CLAIM_TYPE: { icon: 'receipt', tone: 'orange' },
  ROLE: { icon: 'shield', tone: 'purple' }, DEPARTMENT: { icon: 'building', tone: 'green' }, WORKFLOW: { icon: 'switch', tone: 'pink' },
};
const KIND_ORDER: SearchKind[] = ['PERSON', 'LEAVE', 'CLAIM', 'USER', 'DEPARTMENT', 'ROLE', 'LEAVE_TYPE', 'CLAIM_TYPE', 'HOLIDAY', 'WORKFLOW'];

/** The chips across the top: narrow results to one area. */
const SCOPES = {
  all: null,
  people: ['PERSON', 'USER'],
  leave: ['LEAVE', 'LEAVE_TYPE', 'HOLIDAY'],
  claims: ['CLAIM', 'CLAIM_TYPE'],
  admin: ['ROLE', 'DEPARTMENT', 'WORKFLOW'],
  pages: [],
} as const satisfies Record<string, readonly SearchKind[] | null>;
type Scope = keyof typeof SCOPES;

type Option = {
  id: string; to: string; group: string; title: string; meta: string;
  lead: ReactNode; trail?: ReactNode;
  /** What "Recently opened" remembers. */
  recent: Recent;
};
type Recent = { to: string; title: string; meta: string; icon: IconName; tone: Tone };

// ---------- recently opened (this device only) ----------

const RECENT_KEY = 'hrms-search-recent';
function readRecent(): Recent[] {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(list) ? list.slice(0, 6) : [];
  } catch {
    return [];
  }
}
function remember(r: Recent) {
  try {
    const list = [r, ...readRecent().filter((x) => x.to !== r.to)].slice(0, 6);
    localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  } catch {
    // storage blocked: nothing is remembered
  }
}
function forgetRecent() {
  try {
    localStorage.removeItem(RECENT_KEY);
  } catch {
    // storage blocked
  }
}

/** Waits until typing pauses, so each keystroke doesn't send a request. */
function useDebounced(value: string, ms: number) {
  const [out, setOut] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setOut(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return out;
}

/** Bolds the part of the text that matches what was typed. */
function highlight(text: string, q: string): ReactNode {
  const i = q ? text.toLowerCase().indexOf(q.toLowerCase()) : -1;
  if (i < 0) return text;
  return <>{text.slice(0, i)}<mark>{text.slice(i, i + q.length)}</mark>{text.slice(i + q.length)}</>;
}

/**
 * Search everything: pages and actions you can open, and records (people, leave, claims, settings) from the API,
 * which only returns what your permissions allow. ↑↓ to move, Enter to open, Tab to the area chips, Esc to close.
 */
export function SearchPalette({ pages, onClose }: { pages: SearchPage[]; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const id = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [q, setQ] = useState('');
  const [scope, setScope] = useState<Scope>('all');
  const [active, setActive] = useState(0);
  const [recent, setRecent] = useState(readRecent);
  const term = q.trim();
  const debounced = useDebounced(term, 200);
  const searching = term.length >= 2;
  const search = useQuery({
    queryKey: ['search', debounced],
    queryFn: () => api.search(debounced),
    enabled: debounced.length >= 2,
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    retry: (count, error) => !(error instanceof ApiError && error.problem.status < 500) && count < 1,
  });

  // Return focus to whatever opened the search when it closes.
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    inputRef.current?.focus();
    return () => opener?.focus?.();
  }, []);

  // ---- options ----
  const lower = term.toLowerCase();
  const matchedPages = term ? pages.filter((p) => `${p.label} ${p.keywords ?? ''}`.toLowerCase().includes(lower)) : pages;
  const hits = searching && search.data ? search.data.hits : [];
  const inScope = (k: SearchKind) => scope === 'all' || (SCOPES[scope] as readonly SearchKind[]).includes(k);
  const counts: Record<Scope, number> = {
    all: hits.length + matchedPages.length,
    people: hits.filter((h) => (SCOPES.people as readonly SearchKind[]).includes(h.kind)).length,
    leave: hits.filter((h) => (SCOPES.leave as readonly SearchKind[]).includes(h.kind)).length,
    claims: hits.filter((h) => (SCOPES.claims as readonly SearchKind[]).includes(h.kind)).length,
    admin: hits.filter((h) => (SCOPES.admin as readonly SearchKind[]).includes(h.kind)).length,
    pages: matchedPages.length,
  };

  const pageOption = (p: SearchPage, group: string): Option => ({
    id: `page:${p.to}`, to: p.to, group, title: p.label, meta: p.hint ?? '',
    lead: <span className="sp-lead sp-lead--page"><Icon name={p.icon} size={16} /></span>,
    trail: p.action ? <span className="sp-kbd-hint">{t('search.create')}</span> : undefined,
    recent: { to: p.to, title: p.label, meta: '', icon: p.icon, tone: 'blue' },
  });
  const hitOption = (h: SearchHit): Option => {
    const k = KIND[h.kind];
    return {
      id: `${h.kind}:${h.id}`, to: h.link, group: t(`search.groups.${h.kind}`), title: h.title, meta: describe(h),
      lead: h.kind === 'PERSON' || h.kind === 'USER' ? <Avatar name={h.title} size={32} /> : <Glyph icon={k.icon} tone={k.tone} size="lg" />,
      trail: trailFor(h),
      recent: { to: h.link, title: h.title, meta: describe(h), icon: k.icon, tone: k.tone },
    };
  };

  let options: Option[];
  if (!term) {
    // Before typing: what you opened recently, then quick actions and pages.
    options = [
      ...(scope === 'all' ? recent.map((r) => ({
        id: `recent:${r.to}`, to: r.to, group: t('search.recent'), title: r.title, meta: r.meta,
        lead: <Glyph icon={r.icon} tone={r.tone} size="lg" />, recent: r,
      })) : []),
      ...(scope === 'all' ? pages.filter((p) => p.action).map((p) => pageOption(p, t('search.actions'))) : []),
      ...(scope === 'all' || scope === 'pages' ? pages.filter((p) => !p.action).map((p) => pageOption(p, t('search.pages'))) : []),
    ];
  } else {
    options = [
      ...(scope === 'all' || scope === 'pages' ? matchedPages.slice(0, scope === 'pages' ? 20 : 4).map((p) => pageOption(p, t('search.pages'))) : []),
      ...KIND_ORDER.filter(inScope).flatMap((kind) => hits.filter((h) => h.kind === kind).map(hitOption)),
    ];
  }
  const current = Math.min(active, Math.max(0, options.length - 1));

  const open = (o: Option | undefined) => {
    if (!o) return;
    remember(o.recent);
    onClose();
    navigate(o.to);
  };
  const waiting = searching && (debounced !== term || search.isFetching);
  const failed = searching && search.isError;
  const unavailable = failed && search.error instanceof ApiError && search.error.problem.status === 404;
  const nothing = searching && !waiting && !failed && search.data && options.length === 0;

  // Keep keyboard focus inside the dialog.
  const trapTab = (e: ReactKeyboardEvent) => {
    if (e.key !== 'Tab') return;
    const items = [...(dialogRef.current?.querySelectorAll<HTMLElement>('input, button:not([disabled])') ?? [])];
    if (items.length === 0) return;
    const i = items.indexOf(document.activeElement as HTMLElement);
    const next = e.shiftKey ? (i <= 0 ? items.length - 1 : i - 1) : (i === items.length - 1 ? 0 : i + 1);
    e.preventDefault();
    items[next].focus();
  };

  return (
    <div className="sp-layer" role="presentation" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div
        ref={dialogRef}
        className="sp"
        role="dialog"
        aria-modal="true"
        aria-label={t('search.title')}
        onKeyDown={(e) => {
          if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose(); }
          else trapTab(e);
        }}
      >
        <div className="sp-head">
          <div className="sp-input">
            <Icon name="search" size={20} />
            <input
              ref={inputRef}
              value={q}
              onChange={(e) => { setQ(e.target.value); setActive(0); }}
              placeholder={t('search.placeholder')}
              role="combobox"
              aria-expanded={options.length > 0}
              aria-controls={`${id}-list`}
              aria-activedescendant={options[current] ? `${id}-${current}` : undefined}
              aria-autocomplete="list"
              onKeyDown={(e) => {
                if (e.key === 'ArrowDown') { e.preventDefault(); setActive((current + 1) % Math.max(1, options.length)); }
                else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((current - 1 + options.length) % Math.max(1, options.length)); }
                else if (e.key === 'Enter') { e.preventDefault(); open(options[current]); }
              }}
            />
            {waiting ? <span className="sp-spinner" aria-hidden="true" />
              : q && <button type="button" className="sp-clear" onClick={() => { setQ(''); inputRef.current?.focus(); }} aria-label={t('search.clear')}><Icon name="close" size={14} /></button>}
            <button type="button" className="sp-esc" onClick={onClose} aria-label={t('common.close')}>Esc</button>
          </div>
          <div className="sp-scopes" role="group" aria-label={t('search.scopeLabel')}>
            {(Object.keys(SCOPES) as Scope[]).map((s) => (
              <button key={s} type="button" className={scope === s ? 'sp-scope is-on' : 'sp-scope'} aria-pressed={scope === s}
                onClick={() => { setScope(s); setActive(0); inputRef.current?.focus(); }}>
                {t(`search.scopes.${s}`)}
                {searching && search.data && s !== 'all' && counts[s] > 0 && <span className="sp-scope__count">{counts[s]}</span>}
              </button>
            ))}
          </div>
        </div>

        <div className="sp-body">
          {failed ? (
            <div className="sp-empty">
              <Glyph icon="close" tone="pink" size="lg" />
              <strong>{unavailable ? t('search.unavailable') : t('search.failed')}</strong>
              <span>{unavailable ? t('search.unavailableBody') : errorText(search.error)}</span>
            </div>
          ) : nothing ? (
            <div className="sp-empty">
              <Glyph icon="search" tone="blue" size="lg" />
              <strong>{t('search.nothing', { q: term })}</strong>
              <span>{scope === 'all' ? t('search.nothingBody') : t('search.nothingScope')}</span>
              {scope !== 'all' && <button type="button" className="btn small" onClick={() => setScope('all')}>{t('search.everywhere')}</button>}
            </div>
          ) : waiting && options.length === 0 ? (
            <ul className="sp-list" aria-hidden="true">
              {[0, 1, 2, 3].map((i) => <li key={i} className="sp-skeleton"><span /><span><i /><i /></span></li>)}
            </ul>
          ) : (
            <ul id={`${id}-list`} role="listbox" aria-label={t('search.results')} className="sp-list">
              {options.map((o, i) => {
                const groupStart = i === 0 || options[i - 1].group !== o.group;
                return (
                  <SearchOption key={o.id} option={o} id={`${id}-${i}`} active={i === current} term={term}
                    group={groupStart ? o.group : null}
                    groupAction={groupStart && o.group === t('search.recent') && !term
                      ? <button type="button" className="sp-group__action" onClick={() => { forgetRecent(); setRecent([]); }}>{t('search.clearRecent')}</button>
                      : null}
                    onHover={() => setActive(i)} onPick={() => open(o)} />
                );
              })}
            </ul>
          )}
        </div>

        <div className="sp-foot">
          <span className="sp-foot__keys">
            <span><kbd>↑</kbd><kbd>↓</kbd> {t('search.move')}</span>
            <span><kbd>↵</kbd> {t('search.openKey')}</span>
            <span><kbd>Tab</kbd> {t('search.scopeKey')}</span>
          </span>
          <span aria-live="polite">
            {searching && search.data && !failed ? t('search.count', { count: options.length }) : term.length === 1 ? t('search.typeMore') : t('search.hint')}
          </span>
        </div>
      </div>
    </div>
  );
}

function SearchOption({ option: o, id, active, term, group, groupAction, onHover, onPick }: {
  option: Option; id: string; active: boolean; term: string; group: string | null; groupAction: ReactNode;
  onHover: () => void; onPick: () => void;
}) {
  const ref = useRef<HTMLLIElement>(null);
  useEffect(() => {
    if (active) ref.current?.scrollIntoView({ block: 'nearest' });
  }, [active]);
  return (
    <>
      {group && <li role="presentation" className="sp-group"><span>{group}</span>{groupAction}</li>}
      <li ref={ref} id={id} role="option" aria-selected={active} className={active ? 'sp-option is-active' : 'sp-option'}
        onMouseMove={onHover} onMouseDown={(e) => e.preventDefault()} onClick={onPick}>
        <span className="sp-option__lead">{o.lead}</span>
        <span className="sp-option__text">
          <span className="sp-option__title">{highlight(o.title, term)}</span>
          {o.meta && <span className="sp-option__meta">{highlight(o.meta, term)}</span>}
        </span>
        {o.trail && <span className="sp-option__trail">{o.trail}</span>}
        <span className="sp-option__go" aria-hidden="true"><Icon name="chevron" size={14} /></span>
      </li>
    </>
  );
}

// Keys here are built at run time, so use an untyped t.
const tr = (key: string, opts?: Record<string, unknown>) => (i18n.t as (k: string, o?: Record<string, unknown>) => string)(key, opts);

/** Status chip and amount on the right of a result. */
function trailFor(h: SearchHit): ReactNode {
  const parts: ReactNode[] = [];
  if (h.kind === 'CLAIM' && h.amount !== null && h.currency) parts.push(<strong key="a" className="sp-amount">{formatMoney(h.amount, h.currency)}</strong>);
  if ((h.kind === 'LEAVE' || h.kind === 'CLAIM') && h.status) {
    if (h.status === 'PAID') parts.push(<Status key="s" tone="green">{tr('claims.paid')}</Status>);
    else if (h.status in LEAVE_STATUS) {
      const s = LEAVE_STATUS[h.status as keyof typeof LEAVE_STATUS];
      parts.push(<Status key="s" tone={s.tone}>{s.label}</Status>);
    }
  }
  if (h.kind === 'USER' && h.status === 'DISABLED') parts.push(<Status key="s" tone="grey">{tr('users.disabled')}</Status>);
  if ((h.kind === 'LEAVE_TYPE' || h.kind === 'CLAIM_TYPE') && h.status === 'RETIRED') parts.push(<Status key="s" tone="grey">{tr('ls.retired')}</Status>);
  if (h.kind === 'WORKFLOW' && h.status === 'OFF') parts.push(<Status key="s" tone="grey">{tr('common.off')}</Status>);
  return parts.length ? <>{parts}</> : undefined;
}

/** The second line of a result, in the reader's language. */
function describe(h: SearchHit): string {
  const parts: (string | null)[] = [];
  switch (h.kind) {
    case 'LEAVE':
      if (h.date) parts.push(formatRange(h.date, h.endDate ?? h.date));
      parts.push(h.meta);
      break;
    case 'CLAIM':
      if (h.date) parts.push(formatDate(h.date, { day: 'numeric', month: 'short', year: 'numeric' }));
      parts.push(h.meta);
      break;
    case 'DEPARTMENT':
      parts.push(tr('common.person', { count: Number(h.meta ?? 0) }));
      break;
    case 'HOLIDAY':
      if (h.date) parts.push(formatDate(h.date, { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' }));
      break;
    case 'WORKFLOW':
      parts.push(tr(`wf.subjects.${h.tag === 'CLAIM' ? 'CLAIM' : 'LEAVE'}`), h.meta);
      break;
    case 'LEAVE_TYPE':
    case 'CLAIM_TYPE':
      parts.push(tr(`search.groups.${h.kind}`));
      break;
    default:
      parts.push(h.meta);
  }
  return parts.filter(Boolean).join(' · ');
}
