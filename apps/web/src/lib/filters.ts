import { useSearchParams } from 'react-router';

/** One filter in a FilterBar. Values live in the page's URL, so a copied link shows the same view. */
export type FilterDef = {
  /** URL parameter name, e.g. "dept". */
  key: string;
  label: string;
  kind: 'select' | 'search';
  /** select: the choices. '' is "all" and shows `allLabel`. */
  options?: { value: string; label: string }[];
  allLabel?: string;
  placeholder?: string;
  /** Always shown. Others are added from the "+ Filter" menu (or appear when the link sets them). */
  pinned?: boolean;
  /** Value when the URL doesn't say (e.g. this year). It isn't written to the URL. */
  defaultValue?: string;
};

export type UrlFilters = ReturnType<typeof useUrlFilters>;

/** Reads and writes filter values in the URL (replacing history, so Back leaves the page rather than undoing filters). */
export function useUrlFilters(defs: FilterDef[]) {
  const [params, setParams] = useSearchParams();
  const fallback = (key: string) => defs.find((d) => d.key === key)?.defaultValue ?? '';
  const values: Record<string, string> = Object.fromEntries(defs.map((d) => [d.key, params.get(d.key) ?? d.defaultValue ?? '']));

  const update = (change: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params);
    change(next);
    setParams(next, { replace: true });
  };
  const set = (key: string, value: string) => update((next) => {
    if (!value || value === fallback(key)) next.delete(key);
    else next.set(key, value);
  });
  const reset = () => update((next) => defs.forEach((d) => next.delete(d.key)));
  const isActive = (key: string) => values[key] !== fallback(key);

  return {
    defs,
    values,
    set,
    reset,
    isActive,
    active: defs.filter((d) => isActive(d.key)).length,
    /** A string that changes whenever any filter does (for resetting paging). */
    key: defs.map((d) => values[d.key]).join('\u0001'),
    /** Another URL value next to the filters (e.g. rows per page), kept in the shared link too. */
    param: (name: string) => params.get(name),
    setParam: (name: string, value: string | null) => update((next) => (value ? next.set(name, value) : next.delete(name))),
    /** Several URL values at once (separate setParam calls would each start from the old URL and undo each other). */
    setParams: (values: Record<string, string | null>) => update((next) => {
      Object.entries(values).forEach(([name, value]) => (value ? next.set(name, value) : next.delete(name)));
    }),
  };
}
