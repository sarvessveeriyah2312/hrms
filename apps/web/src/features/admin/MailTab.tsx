import { useId, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ApiError, type MailSecurity, type MailSettings, type MailSettingsInput } from '@hrms/api-client';
import { api } from '../../lib/api';
import { errorText, useAccess } from '../../lib/access';
import { relative } from '../../lib/dates';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { Card, Status, Toggle } from '../../components/app/kit';

/** Common providers: fill in the server so admins only add their own account. */
const PRESETS: { key: 'm365' | 'google' | 'ses' | 'mailpit'; host: string; port: number; security: MailSecurity; localOnly?: boolean }[] = [
  { key: 'm365', host: 'smtp.office365.com', port: 587, security: 'STARTTLS' },
  { key: 'google', host: 'smtp.gmail.com', port: 587, security: 'STARTTLS' },
  { key: 'ses', host: 'email-smtp.ap-southeast-1.amazonaws.com', port: 587, security: 'STARTTLS' },
  { key: 'mailpit', host: 'localhost', port: 1025, security: 'NONE', localOnly: true },
];
const DEFAULT_PORT: Record<MailSecurity, number> = { NONE: 25, STARTTLS: 587, SSL: 465 };

type Form = Omit<MailSettingsInput, 'port'> & { port: string };

function toForm(s: MailSettings | null): Form {
  return {
    enabled: s?.enabled ?? true, host: s?.host ?? '', port: String(s?.port ?? 587), security: s?.security ?? 'STARTTLS',
    username: s?.username ?? '', password: '', fromName: s?.fromName ?? '', fromEmail: s?.fromEmail ?? '', replyTo: s?.replyTo ?? '',
  };
}

/** Company → Email: the company's own SMTP server for password resets and other emails. */
export function MailTab() {
  const view = useQuery({ queryKey: ['admin', 'mail'], queryFn: api.admin.mail.get });
  if (view.isError) return <p className="form-error" role="alert">{errorText(view.error)}</p>;
  if (!view.data) return null;
  // Remount the form when the saved settings change, so it starts from what's stored.
  return <MailForm key={view.data.settings?.updatedAt ?? 'none'} />;
}

function MailForm() {
  const { t } = useTranslation();
  const { user } = useAccess();
  const queryClient = useQueryClient();
  const view = useQuery({ queryKey: ['admin', 'mail'], queryFn: api.admin.mail.get });
  const data = view.data!;
  const saved = data.settings;
  const [form, setForm] = useState<Form>(() => toForm(saved));
  // The saved password: kept (default), being replaced (typing a new one), or removed on save.
  const [pwMode, setPwMode] = useState<'keep' | 'replace' | 'remove'>(saved?.passwordSet ? 'keep' : 'replace');
  const [testTo, setTestTo] = useState(user?.email ?? '');
  const [confirmRemove, setConfirmRemove] = useState(false);
  const set = <K extends keyof Form>(k: K, v: Form[K]) => setForm((f) => ({ ...f, [k]: v }));

  const input = (): MailSettingsInput => ({
    ...form,
    port: Number(form.port),
    username: form.username?.trim() || null,
    password: pwMode === 'remove' ? '' : pwMode === 'replace' && form.password ? form.password : null,
    fromName: form.fromName?.trim() || null,
    replyTo: form.replyTo?.trim() || null,
  });
  const refresh = async () => { await queryClient.invalidateQueries({ queryKey: ['admin', 'mail'] }); };
  const save = useMutation({ mutationFn: () => api.admin.mail.save(input()), onSuccess: (v) => queryClient.setQueryData(['admin', 'mail'], v) });
  const test = useMutation({ mutationFn: () => api.admin.mail.test(input(), testTo.trim() || undefined), onSettled: refresh });
  const remove = useMutation({ mutationFn: api.admin.mail.remove, onSuccess: refresh });

  // Field-level messages from the server ("fields": { host: "…" }).
  const problem = (save.error ?? test.error) instanceof ApiError ? (save.error ?? test.error) as ApiError : null;
  const fields = (problem?.problem as { fields?: Record<string, string> } | undefined)?.fields ?? {};
  const fieldError = (k: string) => fields[k] && <span className="error" role="alert">{fields[k]}</span>;
  const generalError = problem && Object.keys(fields).length === 0 ? errorText(save.error ?? test.error) : null;
  const ready = !!form.host.trim() && !!form.port && !!form.fromEmail?.trim();

  const pick = (p: (typeof PRESETS)[number]) => setForm((f) => ({ ...f, host: p.host, port: String(p.port), security: p.security }));
  const setSecurity = (s: MailSecurity) => setForm((f) => ({
    ...f, security: s,
    // Move the port along with the security choice, unless they typed an unusual one.
    port: Object.values(DEFAULT_PORT).includes(Number(f.port)) ? String(DEFAULT_PORT[s]) : f.port,
  }));

  return (
    <div className="mail-layout">
      <div className="col">
        <RouteCard route={data.route} settings={saved} />

        {!data.canStorePasswords && <p className="notice" role="status">{t('mail.noSecretKey')}</p>}

        <Card title={t('mail.serverTitle')} padded>
          <form className="stack" onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate(); }}>
            <Toggle checked={!!form.enabled} onChange={(on) => set('enabled', on)} label={t('mail.useOwn')} hint={t('mail.useOwnHint')} />

            <div className="mail-presets" role="group" aria-label={t('mail.presets')}>
              <span className="wf-label">{t('mail.presets')}</span>
              {PRESETS.filter((p) => !p.localOnly || data.privateHostsAllowed).map((p) => (
                <button key={p.key} type="button" className={form.host === p.host ? 'mail-preset is-on' : 'mail-preset'} onClick={() => pick(p)}>
                  {t(`mail.preset.${p.key}`)}
                </button>
              ))}
            </div>

            {generalError && <p className="form-error" role="alert">{generalError}</p>}

            <div className="field-row mail-server-row">
              <label className="field">
                {t('mail.host')}
                <input value={form.host} onChange={(e) => set('host', e.target.value)} placeholder="smtp.example.com" autoComplete="off" spellCheck={false} required />
                {fieldError('host')}
              </label>
              <label className="field mail-port">
                {t('mail.port')}
                <input value={form.port} onChange={(e) => set('port', e.target.value.replace(/\D/g, ''))} inputMode="numeric" maxLength={5} required />
                {fieldError('port')}
              </label>
            </div>

            <fieldset>
              <legend>{t('mail.security')}</legend>
              <div className="segmented" role="radiogroup" aria-label={t('mail.security')}>
                {(['STARTTLS', 'SSL', 'NONE'] as MailSecurity[]).map((s) => (
                  <label key={s} className={form.security === s ? 'is-on' : undefined}>
                    <input type="radio" name="mail-security" checked={form.security === s} onChange={() => setSecurity(s)} />
                    {t(`mail.sec.${s}`)}
                  </label>
                ))}
              </div>
              <p className="hint">{t(`mail.secHint.${form.security}`)}</p>
              {fieldError('security')}
            </fieldset>

            <section className="mail-signin" aria-labelledby="mail-signin-title">
              <div className="mail-section-head">
                <h3 id="mail-signin-title" className="panel-subhead">{t('mail.signInTitle')}</h3>
                <p className="hint">{t('mail.signInHint')}</p>
              </div>
              <div className="field-row mail-signin__row">
                <label className="field">
                  <FieldLabel optional>{t('mail.username')}</FieldLabel>
                  <input value={form.username ?? ''} onChange={(e) => set('username', e.target.value)} autoComplete="off" spellCheck={false}
                    placeholder={t('mail.usernameExample')} />
                </label>
                <PasswordSlot
                  mode={pwMode}
                  hasSaved={!!saved?.passwordSet}
                  disabled={!data.canStorePasswords}
                  value={form.password ?? ''}
                  onValue={(v) => set('password', v)}
                  onMode={(m) => { setPwMode(m); set('password', ''); }}
                />
              </div>
              <p className="mail-tip"><Icon name="lock" size={14} /><span>{t('mail.appPasswordHint')}</span></p>
            </section>

            <h3 className="panel-subhead">{t('mail.senderTitle')}</h3>
            <div className="field-row">
              <label className="field">
                <FieldLabel optional>{t('mail.fromName')}</FieldLabel>
                <input value={form.fromName ?? ''} onChange={(e) => set('fromName', e.target.value)} maxLength={100} placeholder={t('mail.fromNameExample')} />
                {fieldError('fromName')}
              </label>
              <label className="field">
                <FieldLabel>{t('mail.fromEmail')}</FieldLabel>
                <input type="email" value={form.fromEmail ?? ''} onChange={(e) => set('fromEmail', e.target.value)} placeholder="hr@company.com" required />
                {fieldError('fromEmail')}
              </label>
            </div>
            <label className="field">
              <FieldLabel optional>{t('mail.replyTo')}</FieldLabel>
              <input type="email" value={form.replyTo ?? ''} onChange={(e) => set('replyTo', e.target.value)} placeholder="hr-help@company.com" />
              {fieldError('replyTo')}
            </label>
            <p className="hint">{t('mail.fromHint')}</p>

            <div className="panel-actions">
              <button type="submit" className="btn primary" disabled={!ready || save.isPending}>
                {save.isPending ? t('common.saving') : t('mail.save')}
              </button>
              {save.isSuccess && !save.isPending && <span className="hint" role="status"><Icon name="check" size={14} /> {t('mail.saved')}</span>}
            </div>
          </form>
        </Card>
      </div>

      <div className="col">
        <Card title={t('mail.testTitle')} padded>
          <form className="stack" onSubmit={(e) => { e.preventDefault(); if (ready) test.mutate(); }}>
            <p className="hint">{t('mail.testBody')}</p>
            <label className="field">
              {t('mail.testTo')}
              <input type="email" value={testTo} onChange={(e) => setTestTo(e.target.value)} />
              {fieldError('to')}
            </label>
            <div className="panel-actions">
              <button type="submit" className="btn" disabled={!ready || test.isPending}>
                {test.isPending ? t('mail.testing') : t('mail.sendTest')}
              </button>
            </div>
            {test.data && (
              <div className={test.data.ok ? 'mail-result is-ok' : 'mail-result is-bad'} role="status">
                <Icon name={test.data.ok ? 'check' : 'close'} size={16} />
                <div>
                  <strong>{test.data.ok ? t('mail.testOk') : t('mail.testFailed')}</strong>
                  <span>{test.data.message}</span>
                  {test.data.detail && <details><summary>{t('mail.details')}</summary><code>{test.data.detail}</code></details>}
                </div>
              </div>
            )}
          </form>
        </Card>

        {saved && (
          <Card title={t('mail.removeTitle')} padded>
            <div className="stack">
              <p className="hint">{t('mail.removeBody')}</p>
              {remove.error && <p className="form-error" role="alert">{errorText(remove.error)}</p>}
              {confirmRemove ? (
                <div className="panel-actions">
                  <button type="button" className="btn reject" onClick={() => remove.mutate()} disabled={remove.isPending}>{t('mail.removeConfirm')}</button>
                  <button type="button" className="btn" onClick={() => setConfirmRemove(false)}>{t('common.cancel')}</button>
                </div>
              ) : (
                <div className="panel-actions">
                  <button type="button" className="btn" onClick={() => setConfirmRemove(true)}>{t('mail.removeButton')}</button>
                </div>
              )}
            </div>
          </Card>
        )}
      </div>
    </div>
  );
}

/** A field's label with "Optional" on the same line, so fields side by side stay aligned. */
function FieldLabel({ children, optional }: { children: ReactNode; optional?: boolean }) {
  const { t } = useTranslation();
  return (
    <span className="field-label">
      <span>{children}</span>
      {optional && <span className="field-optional">{t('common.optional')}</span>}
    </span>
  );
}

/**
 * The SMTP password. It's write-only, so a saved one shows as a locked row with Change and Remove;
 * changing opens a password box (with show/hide), and "Keep the saved password" backs out.
 */
function PasswordSlot({ mode, hasSaved, disabled, value, onValue, onMode }: {
  mode: 'keep' | 'replace' | 'remove'; hasSaved: boolean; disabled: boolean; value: string;
  onValue: (v: string) => void; onMode: (m: 'keep' | 'replace' | 'remove') => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const [show, setShow] = useState(false);

  if (hasSaved && mode !== 'replace') {
    const removing = mode === 'remove';
    return (
      <div className="field">
        <span className="field-label" id={`${id}-label`}><span>{t('mail.password')}</span></span>
        <div className={removing ? 'pw-saved is-removing' : 'pw-saved'} role="group" aria-labelledby={`${id}-label`}>
          <Icon name="lock" size={15} />
          <span className="pw-saved__dots" aria-hidden="true">••••••••</span>
          <span className="pw-saved__state">{removing ? t('mail.willRemove') : t('mail.passwordSaved')}</span>
          {removing ? (
            <button type="button" className="pw-saved__btn" onClick={() => onMode('keep')}>{t('mail.undo')}</button>
          ) : (
            <>
              <button type="button" className="pw-saved__btn" onClick={() => onMode('replace')} disabled={disabled}>{t('mail.changePassword')}</button>
              <button type="button" className="pw-saved__btn pw-saved__btn--danger" onClick={() => onMode('remove')}
                aria-label={t('mail.removePassword')}>{t('mail.removeShort')}</button>
            </>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="field">
      <span className="field-label">
        <label htmlFor={`${id}-pw`}>{t('mail.password')}</label>
        {hasSaved
          ? <button type="button" className="field-optional field-optional--action" onClick={() => onMode('keep')}>{t('mail.cancelChange')}</button>
          : <span className="field-optional">{t('common.optional')}</span>}
      </span>
      <div className="pw-input">
        <input id={`${id}-pw`} type={show ? 'text' : 'password'} value={value} autoComplete="new-password" disabled={disabled}
          onChange={(e) => onValue(e.target.value)} placeholder={hasSaved ? t('mail.newPasswordPlaceholder') : ''} autoFocus={hasSaved} />
        <button type="button" className="pw-toggle" onClick={() => setShow((v) => !v)} aria-pressed={show}
          aria-label={show ? t('password.hide') : t('password.show')}>
          <Icon name={show ? 'eyeOff' : 'eye'} size={16} />
        </button>
      </div>
    </div>
  );
}

/** Where the company's email goes right now, and the last test. */
function RouteCard({ route, settings }: { route: 'COMPANY' | 'PLATFORM' | 'LOG'; settings: MailSettings | null }) {
  const { t } = useTranslation();
  const tone = route === 'COMPANY' ? 'green' : route === 'PLATFORM' ? 'blue' : 'orange';
  return (
    <Card padded>
      <div className="mail-route">
        <Glyph icon={route === 'LOG' ? 'file' : 'message'} tone={tone} size="lg" />
        <div>
          <span className="wf-label">{t('mail.routeLabel')}</span>
          <strong>{route === 'COMPANY' ? t('mail.route.COMPANY', { host: `${settings?.host}:${settings?.port}` }) : t(`mail.route.${route}`)}</strong>
          <span className="hint">{t(`mail.routeHint.${route}`)}</span>
        </div>
        {settings?.lastTestAt && (
          <Status tone={settings.lastTestOk ? 'green' : 'red'}>
            {settings.lastTestOk ? t('mail.lastTestOk', { when: relative(settings.lastTestAt) }) : t('mail.lastTestBad', { when: relative(settings.lastTestAt) })}
          </Status>
        )}
      </div>
      {settings && settings.lastTestOk === false && settings.lastTestError && <p className="hint mail-route__err">{settings.lastTestError}</p>}
      {settings && !settings.enabled && <p className="hint mail-route__err">{t('mail.savedButOff')}</p>}
    </Card>
  );
}
