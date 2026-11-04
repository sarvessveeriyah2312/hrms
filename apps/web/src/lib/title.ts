import { useEffect, useSyncExternalStore } from 'react';
import { PRODUCT_NAME } from '../brand';

/**
 * The browser tab title. Pages register their name (PageHeader does it), page-level tabs add the open tab,
 * and the app shell adds the unread count and company:  "(3) Calendar · Leave · Demo Sdn Bhd | HRMS".
 */
type Part = 'page' | 'section' | 'detail';
const parts: Record<Part, { value: string | null; owner: symbol | null }> = {
  page: { value: null, owner: null },
  section: { value: null, owner: null },
  detail: { value: null, owner: null },
};
const listeners = new Set<() => void>();
let snapshot = '';

function emit() {
  snapshot = [parts.detail.value, parts.section.value, parts.page.value].filter(Boolean).join(' · ');
  listeners.forEach((l) => l());
}

function useTitlePart(part: Part, value: string | null | undefined) {
  useEffect(() => {
    if (!value) return;
    const owner = Symbol(part);
    parts[part] = { value, owner };
    emit();
    return () => {
      // Only clear if a newer page or tab hasn't taken the slot already.
      if (parts[part].owner === owner) {
        parts[part] = { value: null, owner: null };
        emit();
      }
    };
  }, [part, value]);
}

/** The page's name, e.g. "Leave". */
export const usePageTitle = (title: string | null | undefined) => useTitlePart('page', title);
/** The open tab on a page, e.g. "Calendar". */
export const useSectionTitle = (label: string | null | undefined) => useTitlePart('section', label);
/** Something open on top of the page, e.g. a request panel. */
export const useDetailTitle = (label: string | null | undefined) => useTitlePart('detail', label);

/** Inside the app: composes the registered parts with the company and unread count. */
export function useAppTitle({ company, unread }: { company?: string | null; unread: number }) {
  const page = useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => snapshot,
  );
  useEffect(() => {
    const main = [page, company].filter(Boolean).join(' · ');
    document.title = `${unread > 0 ? `(${unread > 99 ? '99+' : unread}) ` : ''}${main ? `${main} | ` : ''}${PRODUCT_NAME}`;
  }, [page, company, unread]);
}

/** Pages outside the app (landing, login): a fixed title. */
export function usePublicTitle(title: string | null) {
  useEffect(() => {
    document.title = title ? `${title} | ${PRODUCT_NAME}` : PRODUCT_NAME;
  }, [title]);
}
