import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../lib/api';
import { errorText } from '../../lib/access';
import { usePublicTitle } from '../../lib/title';
import { Logo } from '../../components/Logo';
import { Icon } from '../../components/Icon';
import { LanguageSwitcher } from '../../components/LanguageSwitcher';
import '../../app/app.css';

/**
 * Forgot password: we email a one-time link. The answer is the same whether or not an account matches,
 * so this page never says which emails have accounts.
 */
export function ForgotPasswordPage() {
  const { t } = useTranslation();
  usePublicTitle(t('password.forgotTitle'));
  const [login, setLogin] = useState('');
  const [code, setCode] = useState('');
  const [showCode, setShowCode] = useState(false);
  const send = useMutation({ mutationFn: () => api.password.forgot(login.trim(), code.trim() || undefined) });

  return (
    <div className="auth">
      <form onSubmit={(e) => { e.preventDefault(); if (login.trim()) send.mutate(); }} noValidate>
        <Logo />
        {send.isSuccess ? (
          <>
            <span className="auth-icon"><Icon name="check" size={22} /></span>
            <h1>{t('password.checkEmail')}</h1>
            <p className="muted">{t('password.sentBody', { login: login.trim() })}</p>
            <p className="hint">{t('password.mobileOnly')}</p>
            <button type="button" className="btn" onClick={() => send.reset()}>{t('password.tryAgain')}</button>
          </>
        ) : (
          <>
            <h1>{t('password.forgotTitle')}</h1>
            <p className="muted">{t('password.forgotBody')}</p>
            {send.error && <p className="form-error" role="alert">{errorText(send.error)}</p>}
            <label className="field">
              {t('auth.email')}
              <input autoComplete="username" inputMode="email" value={login} onChange={(e) => setLogin(e.target.value)} autoFocus required />
            </label>
            {showCode ? (
              <label className="field">
                {t('auth.companyCode')}
                <input autoComplete="organization" autoCapitalize="characters" placeholder={t('auth.codeExample')} value={code}
                  onChange={(e) => setCode(e.target.value)} />
                <span className="hint">{t('password.codeHint')}</span>
              </label>
            ) : (
              <button type="button" className="link-button" onClick={() => setShowCode(true)}>{t('auth.haveCode')}</button>
            )}
            <button className="btn primary" type="submit" disabled={send.isPending || !login.trim()}>
              {send.isPending ? t('password.sending') : t('password.sendLink')}
            </button>
          </>
        )}
        <div className="auth-foot">
          <Link className="auth-back" to="/login">{t('password.backToSignIn')}</Link>
          <LanguageSwitcher />
        </div>
      </form>
    </div>
  );
}
