import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '../../components/Icon';
import { MIN_PASSWORD } from '../../lib/password';


/** A rough strength for the meter: length counts most, mixing kinds of characters helps a little. */
function strength(p: string): 0 | 1 | 2 | 3 {
  if (p.length < MIN_PASSWORD) return 0;
  const kinds = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(p)).length;
  if (p.length >= 16 || (p.length >= 12 && kinds >= 3)) return 3;
  if (p.length >= 12 || kinds >= 3) return 2;
  return 1;
}

/** New password + confirm, with live checks. `serverError` shows the API's reason (e.g. "too common"). */
export function NewPasswordFields({ value, confirm, onValue, onConfirm, serverError }: {
  value: string; confirm: string; onValue: (v: string) => void; onConfirm: (v: string) => void; serverError?: string | null;
}) {
  const { t } = useTranslation();
  const id = useId();
  const [show, setShow] = useState(false);
  const level = strength(value);
  const longEnough = value.length >= MIN_PASSWORD;
  const matches = confirm.length > 0 && confirm === value;

  return (
    <>
      <div className="field">
        <label htmlFor={`${id}-new`}>{t('password.new')}</label>
        <div className="pw-input">
          <input id={`${id}-new`} type={show ? 'text' : 'password'} autoComplete="new-password" value={value}
            onChange={(e) => onValue(e.target.value)} maxLength={200} aria-describedby={`${id}-rules`} required />
          <button type="button" className="pw-toggle" onClick={() => setShow((s) => !s)} aria-pressed={show}
            aria-label={show ? t('password.hide') : t('password.show')}>
            <Icon name={show ? 'eyeOff' : 'eye'} size={16} />
          </button>
        </div>
        <div className={`pw-meter pw-meter--${level}`} aria-hidden="true"><span /><span /><span /></div>
        <ul id={`${id}-rules`} className="pw-rules">
          <li className={longEnough ? 'is-ok' : undefined}>
            <Icon name={longEnough ? 'check' : 'close'} size={12} />{t('password.ruleLength', { n: MIN_PASSWORD })}
          </li>
          <li className="pw-tip">{t('password.tip')}</li>
        </ul>
        {serverError && <span className="error" role="alert">{serverError}</span>}
      </div>
      <label className="field">
        {t('password.confirm')}
        <input type={show ? 'text' : 'password'} autoComplete="new-password" value={confirm} onChange={(e) => onConfirm(e.target.value)}
          maxLength={200} required />
        {confirm.length > 0 && !matches && <span className="error">{t('password.noMatch')}</span>}
      </label>
    </>
  );
}
