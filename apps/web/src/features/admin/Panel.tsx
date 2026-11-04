import { useTranslation } from 'react-i18next';

export { Panel } from '../../components/app/Panel';

/** Shown once after creating a user or resetting a password. */
export function TemporaryPassword({ name, password, reset }: { name: string; password: string; reset: boolean }) {
  const { t } = useTranslation();
  return (
    <div className="notice" role="status">
      <strong>{t('users.tempFor', { name })}</strong>
      <code className="secret">{password}</code>
      <span className="muted">
        {t('users.tempOnce')}
        {reset && ` ${t('users.tempReset')}`}
      </span>
      <button type="button" className="btn" onClick={() => void navigator.clipboard?.writeText(password)}>{t('users.copyPassword')}</button>
    </div>
  );
}
