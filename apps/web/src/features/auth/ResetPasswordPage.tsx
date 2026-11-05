import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { usePublicTitle } from '../../lib/title';
import { Logo } from '../../components/Logo';
import { Icon } from '../../components/Icon';
import { LanguageSwitcher } from '../../components/LanguageSwitcher';
import { NewPasswordFields } from '../account/NewPasswordFields';
import { passwordReady } from '../../lib/password';
import '../../app/app.css';

/** Opened from the email: /reset-password?token=…  Checks the link, then sets the new password. */
export function ResetPasswordPage() {
  const { t } = useTranslation();
  usePublicTitle(t('password.resetTitle'));
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [value, setValue] = useState('');
  const [confirm, setConfirm] = useState('');
  const link = useQuery({ queryKey: ['reset-link', token], queryFn: () => api.password.checkLink(token), enabled: !!token, retry: false });
  const reset = useMutation({ mutationFn: () => api.password.reset(token, value) });
  const ready = passwordReady(value, confirm);

  const body = !token || link.isError ? (
    <>
      <span className="auth-icon auth-icon--warn"><Icon name="close" size={22} /></span>
      <h1>{t('password.linkDeadTitle')}</h1>
      <p className="muted">{token ? errorText(link.error) : t('password.linkMissing')}</p>
      <Link className="btn primary" to="/forgot-password">{t('password.newLink')}</Link>
    </>
  ) : reset.isSuccess ? (
    <>
      <span className="auth-icon"><Icon name="check" size={22} /></span>
      <h1>{t('password.doneTitle')}</h1>
      <p className="muted">{t('password.doneBody')}</p>
      <Link className="btn primary" to="/login">{t('auth.submit')}</Link>
    </>
  ) : !link.data ? (
    <p className="muted" role="status">{t('common.loading')}</p>
  ) : (
    <>
      <h1>{t('password.resetTitle')}</h1>
      <p className="muted">{t('password.resetFor', { company: link.data.companyName, email: link.data.email })}</p>
      <NewPasswordFields value={value} confirm={confirm} onValue={setValue} onConfirm={setConfirm}
        serverError={reset.error ? errorText(reset.error) : null} />
      <button className="btn primary" type="submit" disabled={!ready || reset.isPending}>
        {reset.isPending ? t('common.saving') : t('password.save')}
      </button>
      <p className="hint">{t('password.signsOut')}</p>
    </>
  );

  return (
    <div className="auth">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) reset.mutate(); }} noValidate>
        <Logo />
        {body}
        <div className="auth-foot">
          <Link className="auth-back" to="/login">{t('password.backToSignIn')}</Link>
          <LanguageSwitcher />
        </div>
      </form>
    </div>
  );
}
