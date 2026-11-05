import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { PAGE_SIZES, usePaging } from '../../lib/paging';
import { Pager } from './Pager';

/**
 * The shared data table: columns, optional group rows, loading and empty rows, and pagination with a page-size
 * picker. Rows are paged in the browser; pass everything you want the person to page through.
 *
 *   <Table rows={lines} rowKey={(l) => l.id} label="Leave by person" columns={[
 *     { key: 'name', header: 'Person', rowHeader: true, cell: (l) => l.name },
 *     { key: 'days', header: 'Days', align: 'end', cell: (l) => l.days },
 *   ]} />
 */
export type Column<T> = {
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** 'end' right-aligns with tabular figures (numbers). */
  align?: 'start' | 'end';
  /** Render this cell as the row's header (<th scope="row">); usually the first column. */
  rowHeader?: boolean;
  /** Extra class for the cell, e.g. to flag a negative balance. */
  cellClass?: (row: T) => string | undefined;
  headerClass?: string;
};

/** Rows that share a group get a header row above them (repeated at the top of each page). */
export type TableGroup = { key: string; label: ReactNode };


export function Table<T>({
  columns, rows, rowKey, label, groupOf, loading = false, empty, pageSize: initialPageSize = 25,
  pageSizes = PAGE_SIZES, className, resetKey, onPageSizeChange,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Accessible name for the table (and its pagination). */
  label: string;
  groupOf?: (row: T) => TableGroup;
  loading?: boolean;
  /** Shown in a full-width row when there's nothing to show (and not loading). */
  empty?: ReactNode;
  pageSize?: number;
  /** Choices in the page-size picker; pass [] to hide the picker. */
  pageSizes?: number[];
  className?: string;
  /** Change it when filters change, to go back to the first page (e.g. `${year}-${department}`). */
  resetKey?: string;
  /** Makes the page size controlled (e.g. kept in a shareable URL); `pageSize` is then the current size. */
  onPageSizeChange?: (size: number) => void;
}) {
  const { t } = useTranslation();
  const paging = usePaging(rows, { pageSize: initialPageSize, resetKey, onPageSizeChange });
  const { visible, total } = paging;
  const span = columns.length;

  const body: ReactNode[] = [];
  let lastGroup: string | null = null;
  for (const row of visible) {
    const group = groupOf?.(row);
    if (group && group.key !== lastGroup) {
      lastGroup = group.key;
      body.push(
        <tr key={`g:${group.key}`} className="ui-table__group">
          <th scope="rowgroup" colSpan={span}>{group.label}</th>
        </tr>,
      );
    }
    body.push(
      <tr key={rowKey(row)}>
        {columns.map((c) => {
          const cls = [c.align === 'end' && 'num', c.cellClass?.(row)].filter(Boolean).join(' ') || undefined;
          return c.rowHeader
            ? <th key={c.key} scope="row" className={cls}>{c.cell(row)}</th>
            : <td key={c.key} className={cls}>{c.cell(row)}</td>;
        })}
      </tr>,
    );
  }

  return (
    <div className="ui-table-wrap">
      <div className="table-scroll">
        <table className={['ui-table', className].filter(Boolean).join(' ')} aria-label={label}>
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.key} scope="col" className={[c.align === 'end' && 'num', c.headerClass].filter(Boolean).join(' ') || undefined}>{c.header}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {body}
            {loading && total === 0 && <tr><td colSpan={span} className="muted">{t('common.loading')}</td></tr>}
            {!loading && total === 0 && <tr><td colSpan={span} className="muted">{empty ?? t('table.empty')}</td></tr>}
          </tbody>
        </table>
      </div>
      <Pager {...paging} label={label} pageSizes={pageSizes} />
    </div>
  );
}
