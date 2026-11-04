import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { refreshSession, useSession } from '../lib/api';

/** Guards the signed-in app. On first load it tries the refresh cookie before sending you to the login page. */
export function RequireAuth() {
  const { t } = useTranslation();
  const session = useSession();
  const location = useLocation();

  useEffect(() => {
    if (session.status === 'unknown') void refreshSession();
  }, [session.status]);

  if (session.status === 'unknown') {
    return <div className="auth-check" role="status">{t('auth.checking')}</div>;
  }
  if (session.status === 'signed-out') {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  return <Outlet />;
}
