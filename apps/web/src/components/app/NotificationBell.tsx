import { useEffect, useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { AppNotification } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { relative, toTone } from '../../lib/dates';
import { Glyph } from '../Glyph';
import { Icon, type IconName } from '../Icon';

const ICON: Record<AppNotification['kind'], IconName> = {
  APPROVAL_WAITING: 'inbox',
  REQUEST_APPROVED: 'check',
  REQUEST_REJECTED: 'close',
  REQUEST_RETURNED: 'switch',
  REQUEST_CANCELLED: 'close',
  LEAVE_RECORDED: 'calendar',
  CLAIM_PAID: 'receipt',
};

/**
 * The bell in the top bar: unread count, and a list where opening an item marks it read and goes to it.
 * Checks for new ones every 30 seconds and when the window regains focus.
 */
export function NotificationBell() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const inbox = useQuery({
    queryKey: ['notifications'],
    queryFn: () => api.notifications.list(30),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  });
  const unread = inbox.data?.unread ?? 0;

  // A new notification usually means the inbox or a request changed too.
  const lastSeen = useRef<string | undefined>(undefined);
  useEffect(() => {
    const newest = inbox.data?.items[0]?.id;
    if (lastSeen.current && newest && newest !== lastSeen.current) {
      void queryClient.invalidateQueries({ queryKey: ['approvals'] });
      void queryClient.invalidateQueries({ queryKey: ['leave'] });
    }
    lastSeen.current = newest;
  }, [inbox.data, queryClient]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const markAll = useMutation({
    mutationFn: api.notifications.readAll,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });
  const openItem = (n: AppNotification) => {
    setOpen(false);
    if (!n.readAt) {
      // Show it as read straight away; the server confirms in the background.
      queryClient.setQueryData<{ unread: number; items: AppNotification[] }>(['notifications'], (d) => d && ({
        unread: Math.max(0, d.unread - 1),
        items: d.items.map((x) => (x.id === n.id ? { ...x, readAt: new Date().toISOString() } : x)),
      }));
      void api.notifications.read(n.id).finally(() => queryClient.invalidateQueries({ queryKey: ['notifications'] }));
    }
    navigate(n.link);
  };

  return (
    <div className="bell" ref={ref}>
      <button
        type="button"
        className="ui-theme-toggle bell__button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={unread > 0 ? t('bell.labelUnread', { count: unread }) : t('bell.title')}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="bell" size={18} />
        {unread > 0 && <span className="bell__badge" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
      </button>

      {open && (
        <div id={panelId} className="bell__panel" role="dialog" aria-label={t('bell.title')}>
          <header className="bell__head">
            <strong>{t('bell.title')}</strong>
            <button type="button" className="link-button" onClick={() => markAll.mutate()} disabled={unread === 0 || markAll.isPending}>
              {t('bell.markAll')}
            </button>
          </header>
          {inbox.isError && <p className="form-error bell__error" role="alert">{errorText(inbox.error)}</p>}
          {inbox.data && inbox.data.items.length === 0 ? (
            <div className="bell__empty">
              <Glyph icon="bell" tone="blue" size="lg" />
              <p>{t('bell.empty')}</p>
            </div>
          ) : (
            <ul className="bell__list">
              {inbox.data?.items.map((n) => (
                <li key={n.id}>
                  <button type="button" className={n.readAt ? 'bell__item' : 'bell__item is-unread'} onClick={() => openItem(n)}>
                    <Glyph icon={ICON[n.kind] ?? 'bell'} tone={toTone(n.tone, 'blue')} />
                    <span className="bell__text">
                      <span className="bell__title">{n.title}</span>
                      {n.body && <span className="bell__body">{n.body}</span>}
                      <span className="bell__time">{relative(n.createdAt)}</span>
                    </span>
                    {!n.readAt && <span className="bell__dot" aria-label={t('bell.unread')} />}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
