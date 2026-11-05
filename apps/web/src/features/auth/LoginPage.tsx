import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { ApiError } from '@hrms/api-client';
import { signIn } from '../../lib/api';
import { errorText } from '../../lib/access';
import { Logo } from '../../components/Logo';
import { usePublicTitle } from '../../lib/title';
import { LanguageSwitcher } from '../../components/LanguageSwitcher';
import i18n from '../../i18n';
import '../../app/app.css';

// Messages are read when validating, so they follow the current language.
const schema = z.object({
  username: z.string().trim().min(3, { error: () => i18n.t('auth.needUsername') }),
  password: z.string().min(1, { error: () => i18n.t('auth.needPassword') }),
  companyCode: z.string().trim().max(20).optional(),
});
type Form = z.infer<typeof schema>;
type Company = { code: string; name: string };

/** Only return to pages inside the app, never to another site (`next=//evil.example`). */
function safeNext(next: string | null) {
  return next && next.startsWith('/app') ? next : '/app';
}

/**
 * Sign-in. Email or mobile number plus password; the company code is optional and only needed when
 * the same login exists in more than one company (the API then lists them, after checking the password).
 */
export function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const location = useLocation();
  const prefill = (location.state ?? {}) as { username?: string; switchCompany?: boolean };
  const [companies, setCompanies] = useState<Company[] | null>(null);
  usePublicTitle(prefill.switchCompany ? t('auth.switchTitle') : t('auth.title'));
  const [showCode, setShowCode] = useState(false);
  const { register, handleSubmit, setError, setValue, getValues, formState } = useForm<Form>({
    defaultValues: { username: prefill.username ?? '' },
  });

  const submit = async (values: Form) => {
    const parsed = schema.safeParse(values);
    if (!parsed.success) {
      for (const issue of parsed.error.issues) {
        setError(issue.path[0] as keyof Form, { message: issue.message });
      }
      return;
    }
    try {
      const { username, password, companyCode } = parsed.data;
      await signIn(username, password, companyCode || undefined);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.problem.code === 'COMPANY_REQUIRED' && error.problem.companies) {
        setCompanies(error.problem.companies);
        return;
      }
      setError('root', { message: errorText(error) });
    }
  };

  const pick = (company: Company) => {
    setValue('companyCode', company.code);
    void submit(getValues());
  };

  return (
    <div className="auth">
      <form onSubmit={handleSubmit(submit)} noValidate>
        <Logo />
        <h1>{prefill.switchCompany ? t('auth.switchTitle') : t('auth.title')}</h1>
        {formState.errors.root && (
          <p className="form-error" role="alert">{formState.errors.root.message}</p>
        )}

        {companies ? (
          <div className="company-picker" role="group" aria-labelledby="company-picker-title">
            <p id="company-picker-title" className="muted">{t('auth.chooseCompany')}</p>
            {companies.map((c) => (
              <button key={c.code} type="button" className="company-option" onClick={() => pick(c)} disabled={formState.isSubmitting}>
                <span className="org-mark" aria-hidden="true">{c.name[0]}</span>
                <span><strong>{c.name}</strong><small>{c.code}</small></span>
              </button>
            ))}
            <button type="button" className="link-button" onClick={() => setCompanies(null)}>{t('auth.back')}</button>
          </div>
        ) : (
          <>
            <label className="field">
              {t('auth.email')}
              <input autoComplete="username" inputMode="email" {...register('username')} />
              {formState.errors.username && <span className="error">{formState.errors.username.message}</span>}
            </label>
            <label className="field">
              {t('auth.password')}
              <input type="password" autoComplete="current-password" autoFocus={!!prefill.username} {...register('password')} />
              {formState.errors.password && <span className="error">{formState.errors.password.message}</span>}
              <Link className="auth-forgot" to="/forgot-password">{t('password.forgotLink')}</Link>
            </label>
            {showCode || prefill.switchCompany ? (
              <label className="field">
                {t('auth.companyCode')}
                <input autoComplete="organization" autoCapitalize="characters" placeholder={t('auth.codeExample')} {...register('companyCode')} />
                <span className="hint">{t('auth.companyCodeHint')}</span>
              </label>
            ) : (
              <button type="button" className="link-button" onClick={() => setShowCode(true)}>{t('auth.haveCode')}</button>
            )}
            <button className="btn primary" type="submit" disabled={formState.isSubmitting}>
              {formState.isSubmitting ? t('auth.submitting') : t('auth.submit')}
            </button>
          </>
        )}
        <div className="auth-foot">
          <Link className="auth-back" to="/">{t('auth.home')}</Link>
          <LanguageSwitcher />
        </div>
      </form>
    </div>
  );
}
