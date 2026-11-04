import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useAccess } from '../lib/access';
import { Card, EmptyState } from '../components/app/kit';

/** Shows the page only when the user holds one of the permissions (and the module is on). */
export function RequirePermission({ any, module, children }: { any: string[]; module?: string; children: ReactNode }) {
  const { t } = useTranslation();
  const access = useAccess();
  const moduleOff = !!module && !access.hasModule(module);
  if (access.canAny(any) && !moduleOff) return <>{children}</>;
  return (
    <Card>
      <EmptyState
        icon="lock"
        tone="pink"
        title={t('access.noAccess')}
        action={<Link className="btn" to="/app">{t('common.backHome')}</Link>}
      >
        {moduleOff ? t('access.moduleOff') : t('access.askHr')}
      </EmptyState>
    </Card>
  );
}
