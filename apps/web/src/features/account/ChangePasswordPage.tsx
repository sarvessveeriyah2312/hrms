import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@hrms/api-client';
import { api, applySession } from '../../lib/api';
import { errorText, useAccess } from '../../lib/access';
import { Card, PageHeader } from '../../components/app/kit';
import { NewPasswordFields } from './NewPasswordFields';
import { passwordReady } from '../../lib/password';

/**
 * Change your password (Account menu → Change password). Also where people land after signing in with a
 * temporary password an admin set: they can't open anything else until they've chosen their own.
 */
export function ChangePasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { user } = useAccess();
  const forced = !!user?.mustChangePassword;
  const [current, setCurrent] = useState('');
  const [value, setValue] = useState('');
  const [confirm, setConfirm] = useState('');
  const [done, setDone] = useState(false);
  const change = useMutation({
    mutationFn: () => api.password.change(current, value),
    onSuccess: (session) => {
      applySession(session);
      setCurrent('');
      setValue('');
      setConfirm('');
      if (forced) navigate('/app', { replace: true });
      else setDone(true);
    },
  });
  const fieldError = change.error instanceof ApiError ? change.error.problem.code : null;
  const currentWrong = fieldError === 'WRONG_PASSWORD';
  const ready = current.length > 0 && passwordReady(value, confirm);

  return (
    <>
      <PageHeader title={t('password.changeTitle')} subtitle={forced ? undefined : t('password.changeSubtitle')} />
      {forced && <p className="notice notice--action" role="status">{t('password.forcedBody')}</p>}
      {done && <p className="notice" role="status">{t('password.changedNotice')}</p>}
      <Card padded className="pw-card">
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            setDone(false);
            if (ready) change.mutate();
          }}
        >
          {change.error && !currentWrong && !fieldError?.startsWith('PASSWORD_') && (
            <p className="form-error" role="alert">{errorText(change.error)}</p>
          )}
          <label className="field">
            {forced ? t('password.temporary') : t('password.current')}
            <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)}
              maxLength={200} required autoFocus />
            {currentWrong && <span className="error" role="alert">{errorText(change.error)}</span>}
          </label>
          <NewPasswordFields value={value} confirm={confirm} onValue={setValue} onConfirm={setConfirm}
            serverError={fieldError?.startsWith('PASSWORD_') ? errorText(change.error) : null} />
          <div className="panel-actions">
            <button type="submit" className="btn primary" disabled={!ready || change.isPending}>
              {change.isPending ? t('common.saving') : t('password.save')}
            </button>
          </div>
          <p className="hint">{t('password.othersSignedOut')}</p>
        </form>
      </Card>
    </>
  );
}
