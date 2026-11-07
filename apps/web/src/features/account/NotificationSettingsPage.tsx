import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { NotificationPreference } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Card, PageHeader } from '../../components/app/kit';

/** Groups of notification kinds, in the order shown. */
const GROUPS = [
  { key: 'approvals', kinds: ['APPROVAL_WAITING', 'APPROVAL_OVERDUE', 'APPROVAL_BACKUP'] },
  { key: 'requests', kinds: ['REQUEST_APPROVED', 'REQUEST_REJECTED', 'REQUEST_RETURNED', 'REQUEST_CANCELLED', 'LEAVE_RECORDED', 'CLAIM_PAID'] },
  { key: 'work', kinds: ['SCHEDULE_PUBLISHED', 'PAYSLIP_PUBLISHED', 'TAX_FORM_PUBLISHED', 'LETTER_ISSUED'] },
  { key: 'company', kinds: ['ANNOUNCEMENT', 'POLICY_PUBLISHED', 'GRIEVANCE_UPDATE'] },
] as const;

/** Email and push on or off for each kind of notification. In-app notifications (the bell) always arrive. */
export function NotificationSettingsPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const prefs = useQuery({ queryKey: ['notifications', 'preferences'], queryFn: () => api.notifications.preferences() });
  const [saved, setSaved] = useState(false);
  const save = useMutation({
    mutationFn: (p: NotificationPreference[]) => api.notifications.savePreferences(p),
    onSuccess: (data) => { queryClient.setQueryData(['notifications', 'preferences'], data); setSaved(true); },
  });
  const byKind = new Map((prefs.data ?? []).map((p) => [p.kind, p]));
  const change = (p: NotificationPreference) => { setSaved(false); save.mutate([p]); };
  const setAll = (channel: 'email' | 'push', on: boolean) => {
    setSaved(false);
    save.mutate((prefs.data ?? []).map((p) => ({ ...p, [channel]: on })));
  };

  return (
    <>
      <PageHeader title={t('notifSettings.title')} subtitle={t('notifSettings.subtitle')} />
      {prefs.isError && <p className="form-error" role="alert">{errorText(prefs.error)}</p>}
      {save.error && <p className="form-error" role="alert">{errorText(save.error)}</p>}
      <p className="hint" role="status">{save.isPending ? t('common.saving') : saved ? t('notifSettings.saved') : ''}</p>
      <div className="panel-actions">
        <button type="button" className="btn small" onClick={() => setAll('email', false)} disabled={!prefs.data}>{t('notifSettings.noEmail')}</button>
        <button type="button" className="btn small" onClick={() => setAll('email', true)} disabled={!prefs.data}>{t('notifSettings.allEmail')}</button>
      </div>
      {GROUPS.map((g) => (
        <Card key={g.key} title={t(`notifSettings.groups.${g.key}`)}>
          <div className="table-scroll">
            <table className="ui-table">
              <thead>
                <tr><th scope="col">{t('notifSettings.event')}</th><th scope="col">{t('notifSettings.email')}</th><th scope="col">{t('notifSettings.push')}</th></tr>
              </thead>
              <tbody>
                {g.kinds.filter((k) => byKind.has(k)).map((k) => {
                  const p = byKind.get(k)!;
                  const label = t(`notifSettings.kinds.${k}`);
                  return (
                    <tr key={k}>
                      <th scope="row">{label}</th>
                      <td><input type="checkbox" checked={p.email} aria-label={`${label}: ${t('notifSettings.email')}`} onChange={(e) => change({ ...p, email: e.target.checked })} /></td>
                      <td><input type="checkbox" checked={p.push} aria-label={`${label}: ${t('notifSettings.push')}`} onChange={(e) => change({ ...p, push: e.target.checked })} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      ))}
      <p className="hint">{t('notifSettings.pushHint')}</p>
    </>
  );
}
