import { useState } from 'react';

export const PAGE_SIZES = [10, 25, 50, 100];

/**
 * Pages a list in the browser. `resetKey`: change it when filters or the search change, to go back to page 1.
 * Spread the result into <Pager {...paging} label="…" /> and render `paging.visible`.
 */
export function usePaging<T>(rows: T[], { pageSize: initial = 25, resetKey, onPageSizeChange }: {
  pageSize?: number;
  resetKey?: string;
  /** Makes the page size controlled (e.g. kept in the URL): `pageSize` is then the current size. */
  onPageSizeChange?: (size: number) => void;
} = {}) {
  const [ownSize, setOwnSize] = useState(initial);
  const pageSize = onPageSizeChange ? initial : ownSize;
  const setPageSize = onPageSizeChange ?? setOwnSize;
  const [page, setPage] = useState(1);
  const [seenKey, setSeenKey] = useState(resetKey);
  if (seenKey !== resetKey) {
    // Adjusting state while rendering (no effect): React re-renders straight away with page 1.
    setSeenKey(resetKey);
    setPage(1);
  }
  const total = rows.length;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  // Filters can shrink the rows under the current page; stay on the last page that exists.
  const current = Math.min(page, pages);
  const start = (current - 1) * pageSize;
  const visible = rows.slice(start, start + pageSize);
  return {
    visible,
    page: current,
    pages,
    total,
    from: total === 0 ? 0 : start + 1,
    to: start + visible.length,
    pageSize,
    onPage: setPage,
    onPageSize: (size: number) => {
      // Keep the first visible row on screen.
      setPage(Math.floor(start / size) + 1);
      setPageSize(size);
    },
  };
}

/**
 * A table's page size kept in the URL under `name` (left out when it's the default), for <Table>:
 * `<Table {...urlPageSize(filters, 'typeSize', 10)} />`. Several tables on a page each use their own name.
 */
export function urlPageSize(params: { param: (name: string) => string | null; setParam: (name: string, value: string | null) => void },
  name: string, fallback = 25) {
  const n = Number(params.param(name));
  return {
    pageSize: PAGE_SIZES.includes(n) ? n : fallback,
    onPageSizeChange: (size: number) => params.setParam(name, size === fallback ? null : String(size)),
  };
}
