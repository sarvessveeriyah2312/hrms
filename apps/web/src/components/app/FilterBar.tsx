import { useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { FilterDef, UrlFilters } from '../../lib/filters';
import { Icon } from '../Icon';
import { Menu } from './kit';

/**
 * Filters for a page or table, kept in the URL: pinned filters always show, others are added from "+ Filter"
 * and removed with ×. "Reset" clears them and "Copy link" shares exactly this view.
 *
 *   const filters = useUrlFilters([{ key: 'dept', label: 'Department', kind: 'select', options, pinned: true }]);
 *   <FilterBar filters={filters} label="Report filters" />
 */
export function FilterBar({ filters, label, children }: { filters: UrlFilters; label: string; children?: ReactNode }) {
  const { t } = useTranslation();
  const [added, setAdded] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);
  const shown = filters.defs.filter((d) => d.pinned || added.includes(d.key) || filters.isActive(d.key));
  const hidden = filters.defs.filter((d) => !shown.includes(d));

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard blocked by the browser; the address bar still has the same link.
    }
  };

  return (
    <div className="filter-bar" role="group" aria-label={label}>
      <div className="filter-bar__fields">
        {shown.map((d) => (
          <FilterField key={d.key} def={d} value={filters.values[d.key]} onChange={(v) => filters.set(d.key, v)}
            onRemove={d.pinned ? undefined : () => { setAdded((a) => a.filter((k) => k !== d.key)); filters.set(d.key, ''); }} />
        ))}
        {hidden.length > 0 && (
          <Menu
            className="filter-add"
            label={t('filters.add')}
            trigger={<><Icon name="plus" size={14} /><span>{t('filters.add')}</span></>}
            items={hidden.map((d) => ({ label: d.label, onSelect: () => setAdded((a) => [...a, d.key]) }))}
          />
        )}
      </div>
      <div className="filter-bar__end">
        {children}
        {filters.active > 0 && (
          <button type="button" className="link-button" onClick={() => { filters.reset(); setAdded([]); }}>
            {t('filters.reset', { count: filters.active })}
          </button>
        )}
        <button type="button" className="btn small" onClick={() => void copy()} aria-live="polite">
          <Icon name={copied ? 'check' : 'link'} size={14} />{copied ? t('filters.copied') : t('filters.copy')}
        </button>
      </div>
    </div>
  );
}

function FilterField({ def, value, onChange, onRemove }: {
  def: FilterDef; value: string; onChange: (v: string) => void; onRemove?: () => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const on = value !== (def.defaultValue ?? '');
  // A pinned search is the bar's main search box: a magnifier instead of a label (the label stays for screen readers).
  if (def.kind === 'search' && def.pinned) {
    return (
      <span className={on ? 'filter-search is-on' : 'filter-search'}>
        <Icon name="search" size={15} />
        <label htmlFor={id} className="sr-only">{def.label}</label>
        <input id={id} type="search" value={value} onChange={(e) => onChange(e.target.value)} placeholder={def.placeholder}
          onKeyDown={(e) => { if (e.key === 'Escape' && value) { e.preventDefault(); onChange(''); } }} />
      </span>
    );
  }
  return (
    <span className={on ? 'filter-field is-on' : 'filter-field'}>
      <label htmlFor={id} className="filter-field__label">{def.label}</label>
      {def.kind === 'select' ? (
        <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
          {def.allLabel !== undefined && <option value="">{def.allLabel}</option>}
          {def.options?.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          {/* A shared link can name a choice this person's list doesn't have (yet); keep it visible. */}
          {value && value !== def.defaultValue && !def.options?.some((o) => o.value === value) && <option value={value}>{value}</option>}
        </select>
      ) : (
        <input id={id} type="search" value={value} onChange={(e) => onChange(e.target.value)} placeholder={def.placeholder} />
      )}
      {onRemove && (
        <button type="button" className="filter-field__remove" onClick={onRemove} aria-label={t('filters.remove', { name: def.label })}>
          <Icon name="close" size={12} />
        </button>
      )}
    </span>
  );
}
