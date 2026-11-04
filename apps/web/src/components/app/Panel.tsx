import { useEffect, useRef, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useDetailTitle } from '../../lib/title';

/** Side panel for editing one record. Escape or the close button dismisses it; focus moves into it on open. */
export function Panel({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  useDetailTitle(title);

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    // First field if there is one, otherwise the first button (e.g. the temporary-password view).
    (ref.current?.querySelector<HTMLElement>('.panel-body input, .panel-body select, .panel-body textarea')
      ?? ref.current?.querySelector<HTMLElement>('button'))?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      previous?.focus();
    };
  }, [onClose]);

  return (
    <div className="panel-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="panel" role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <header className="panel-head">
          <h2>{title}</h2>
          <button type="button" className="btn" onClick={onClose}>{t('panel.close')}</button>
        </header>
        <div className="panel-body">{children}</div>
      </div>
    </div>
  );
}
