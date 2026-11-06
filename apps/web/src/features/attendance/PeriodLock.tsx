import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { can, errorText, useAccess } from '../../lib/access';
import { formatDate, relative } from '../../lib/dates';
import { Status } from '../../components/app/kit';
import { Panel } from '../../components/app/Panel';
import { Icon } from '../../components/Icon';

const monthLabel = (month: string) => formatDate(`${month}-01`, { month: 'long', year: 'numeric' });

/**
 * A month's payroll lock, beside the month picker on timesheets: a "Locked" chip for everyone, and for people who can
 * lock (attendance:lock) a button that opens the lock / unlock panel.
 */
export function MonthLock({ month }: { month: string }) {
  const { t } = useTranslation();
  const { user } = useAccess();
  const canLock = can(user, 'attendance:lock');
  const [open, setOpen] = useState(false);
  const status = useQuery({ queryKey: ['attendance', 'lock', month], queryFn: () => api.attendance.locks.status(month) });
  const locked = !!status.data?.locked;

  return (
    <>
      {locked && <Status tone="grey">{t('lock.locked')}</Status>}
      {canLock && status.data && (
        <button type="button" className="btn small" onClick={() => setOpen(true)}>
          <Icon name="lock" size={14} />{locked ? t('lock.unlock') : t('lock.lock')}
        </button>
      )}
      {open && status.data && <LockPanel month={month} onClose={() => setOpen(false)} />}
    </>
  );
}

function LockPanel({ month, onClose }: { month: string; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: ['attendance', 'lock', month], queryFn: () => api.attendance.locks.status(month) });
  const [reason, setReason] = useState('');
  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: ['attendance'] });
    setReason('');
  };
  const lock = useMutation({ mutationFn: () => api.attendance.locks.lock(month), onSuccess: done });
  const unlock = useMutation({ mutationFn: () => api.attendance.locks.unlock(month, reason.trim()), onSuccess: done });
  const s = status.data;
  const error = status.error ?? lock.error ?? unlock.error;
  const waiting = (s?.waitingCorrections ?? 0) + (s?.waitingOvertime ?? 0);

  return (
    <Panel title={t('lock.title', { month: monthLabel(month) })} onClose={onClose}>
      {error && <p className="form-error" role="alert">{errorText(error)}</p>}
      {s && (
        <div className="stack">
          <p>{s.locked ? t('lock.isLocked', { who: s.lockedBy ?? '—', when: relative(s.lockedAt!) }) : t('lock.isOpen')}</p>
          <p className="hint">{t('lock.explain')}</p>
          {waiting > 0 && (
            <p className="notice" role="status">
              {t('lock.waiting', { corrections: s.waitingCorrections, overtime: s.waitingOvertime })}
            </p>
          )}
          {s.locked ? (
            <form className="stack" onSubmit={(e) => { e.preventDefault(); unlock.mutate(); }}>
              <label className="field">
                {t('lock.reason')}
                <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} required placeholder={t('lock.reasonHint')} />
              </label>
              <div className="panel-actions">
                <button type="submit" className="btn reject" disabled={!reason.trim() || unlock.isPending}>{t('lock.unlockMonth')}</button>
              </div>
            </form>
          ) : (
            <div className="panel-actions">
              <button type="button" className="btn primary" disabled={lock.isPending} onClick={() => lock.mutate()}>
                <Icon name="lock" size={14} />{t('lock.lockMonth')}
              </button>
            </div>
          )}
          {s.history.length > 0 && (
            <section className="stack">
              <h3 className="panel-subhead">{t('lock.history')}</h3>
              <ul className="lock-history">
                {s.history.map((h, i) => (
                  <li key={i}>
                    <strong>{h.action === 'LOCK' ? t('lock.lockedEvent') : t('lock.unlockedEvent')}</strong>
                    {' · '}{h.actor ?? '—'}{' · '}<span className="muted">{relative(h.at)}</span>
                    {h.reason && <div className="muted">“{h.reason}”</div>}
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      )}
    </Panel>
  );
}
