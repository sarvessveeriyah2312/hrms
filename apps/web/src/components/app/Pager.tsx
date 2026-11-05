import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { PAGE_SIZES } from '../../lib/paging';
import { Icon } from '../Icon';

/** Page numbers to show: always the first, last and the ones around the current page; gaps become "…". */
function pageList(page: number, pages: number): (number | 'gap')[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1);
  const keep = new Set([1, pages, page - 1, page, page + 1]);
  if (page <= 3) [2, 3, 4].forEach((p) => keep.add(p));
  if (page >= pages - 2) [pages - 3, pages - 2, pages - 1].forEach((p) => keep.add(p));
  const sorted = [...keep].filter((p) => p >= 1 && p <= pages).sort((a, b) => a - b);
  const out: (number | 'gap')[] = [];
  sorted.forEach((p, i) => {
    if (i > 0 && p - sorted[i - 1] > 1) out.push('gap');
    out.push(p);
  });
  return out;
}

/**
 * Page controls for a table or list: "1–25 of 132", rows per page, and page buttons. Hidden when there's
 * nothing to page. Use with usePaging (lib/paging.ts): <Pager {...paging} label="People" />.
 */
export function Pager({ label, page, pages, from, to, total, pageSize, pageSizes = PAGE_SIZES, onPage, onPageSize }: {
  label: string; page: number; pages: number; from: number; to: number; total: number;
  pageSize: number; pageSizes?: number[]; onPage: (p: number) => void; onPageSize: (s: number) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  if (total === 0) return null;
  const sizes = [...new Set([...pageSizes, pageSize])].sort((a, b) => a - b);
  const showSizes = pageSizes.length > 0 && total > Math.min(...sizes);

  return (
    <div className="ui-pager">
      <span className="ui-pager__count" aria-live="polite">{t('table.showing', { from, to, total })}</span>
      {showSizes && (
        <label className="ui-pager__size" htmlFor={id}>
          {t('table.rowsPerPage')}
          <select id={id} className="input" value={pageSize} onChange={(e) => onPageSize(Number(e.target.value))}>
            {sizes.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      )}
      {pages > 1 && (
        <nav className="ui-pager__pages" aria-label={t('table.pagination', { name: label })}>
          <button type="button" className="ui-pager__btn" onClick={() => onPage(page - 1)} disabled={page === 1} aria-label={t('table.prev')}>
            <Icon name="chevron" size={14} style={{ transform: 'rotate(180deg)' }} />
          </button>
          {pageList(page, pages).map((p, i) => p === 'gap'
            ? <span key={`gap-${i}`} className="ui-pager__gap" aria-hidden="true">…</span>
            : (
              <button key={p} type="button" className={p === page ? 'ui-pager__btn is-current' : 'ui-pager__btn'}
                onClick={() => onPage(p)} aria-current={p === page ? 'page' : undefined} aria-label={t('table.page', { n: p })}>
                {p}
              </button>
            ))}
          <button type="button" className="ui-pager__btn" onClick={() => onPage(page + 1)} disabled={page === pages} aria-label={t('table.next')}>
            <Icon name="chevron" size={14} />
          </button>
        </nav>
      )}
    </div>
  );
}
