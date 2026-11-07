import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ProfileField, ProfileSectionSpec, SectionData } from '@hrms/api-client';
import { fieldLabel, items, optionLabel } from './sectionText';

type Values = Record<string, string>;

function toValues(spec: ProfileSectionSpec, data: Record<string, unknown> | undefined): Values {
  const out: Values = {};
  for (const f of spec.fields) out[f.key] = data?.[f.key] == null ? '' : String(data[f.key]);
  return out;
}

function blank(spec: ProfileSectionSpec): Values {
  return toValues(spec, undefined);
}

/** Drops empty values so the API sees only what was filled in. */
function compact(v: Values): Record<string, string> {
  return Object.fromEntries(Object.entries(v).filter(([, x]) => x.trim() !== '').map(([k, x]) => [k, x.trim()]));
}

/**
 * The form for one profile section, built from the API's spec. List sections (emergency contacts, children,
 * education) get one block per entry with Add and Remove. `mode` decides the wording: HR saves straight away; the
 * person sends it to HR (with an optional note).
 */
export function SectionForm({ spec, initial, mode, initialNote, busy, error, onSubmit, onCancel }: {
  spec: ProfileSectionSpec;
  initial: SectionData | undefined;
  mode: 'save' | 'request';
  initialNote?: string | null;
  busy: boolean;
  error: string | null;
  onSubmit: (data: SectionData, note?: string) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [one, setOne] = useState<Values>(() => toValues(spec, initial));
  const [list, setList] = useState<Values[]>(() => {
    const existing = items(initial).map((i) => toValues(spec, i));
    return existing.length ? existing : [blank(spec)];
  });
  const [note, setNote] = useState(initialNote ?? '');

  const submit = () => {
    const data: SectionData = spec.list
      ? { items: list.map(compact).filter((i) => Object.keys(i).length > 0) }
      : compact(one);
    onSubmit(data, mode === 'request' ? note.trim() || undefined : undefined);
  };

  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); submit(); }} noValidate>
      {error && <p className="form-error" role="alert">{error}</p>}
      {spec.list ? (
        <>
          {list.map((entry, n) => (
            <fieldset key={n} className="profile-entry">
              <legend>
                {t('profile.entry', { n: n + 1 })}
                <button type="button" className="link-button" onClick={() => setList((l) => (l.length === 1 ? [blank(spec)] : l.filter((_, i) => i !== n)))}>
                  {t('common.remove')}
                </button>
              </legend>
              <div className="field-row">
                {spec.fields.map((f) => (
                  <FieldInput key={f.key} field={f} value={entry[f.key]}
                    onChange={(v) => setList((l) => l.map((x, i) => (i === n ? { ...x, [f.key]: v } : x)))} />
                ))}
              </div>
            </fieldset>
          ))}
          {list.length < spec.maxItems && (
            <button type="button" className="btn small" onClick={() => setList((l) => [...l, blank(spec)])}>
              {t('profile.addEntry')}
            </button>
          )}
          <p className="hint">{t('profile.upTo', { count: spec.maxItems })}</p>
        </>
      ) : (
        <div className="field-row">
          {spec.fields.map((f) => (
            <FieldInput key={f.key} field={f} value={one[f.key]} onChange={(v) => setOne((o) => ({ ...o, [f.key]: v }))} />
          ))}
        </div>
      )}
      {spec.fields.some((f) => f.sensitive) && <p className="hint">{t('profile.sensitiveHint')}</p>}
      {mode === 'request' && (
        <label className="field">
          <span className="field-label">{t('profile.noteForHr')}<span className="field-optional">{t('common.optional')}</span></span>
          <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} placeholder={t('profile.noteHint')} />
        </label>
      )}
      {mode === 'request' && <p className="hint">{t('profile.requestHint')}</p>}
      <div className="panel-actions">
        <button type="submit" className="btn primary" disabled={busy}>{mode === 'request' ? t('profile.sendToHr') : t('common.save')}</button>
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>{t('common.cancel')}</button>
      </div>
    </form>
  );
}

function FieldInput({ field, value, onChange }: { field: ProfileField; value: string; onChange: (v: string) => void }) {
  const { t } = useTranslation();
  const id = useId();
  const label = (
    <span className="field-label">
      {fieldLabel(field.key)}
      {!field.required && <span className="field-optional">{t('common.optional')}</span>}
    </span>
  );
  if (field.type === 'SELECT') {
    return (
      <label className="field" htmlFor={id}>
        {label}
        <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">{t('profile.choose')}</option>
          {field.options.map((o) => <option key={o} value={o}>{optionLabel(o)}</option>)}
        </select>
      </label>
    );
  }
  const type = field.type === 'EMAIL' ? 'email' : field.type === 'PHONE' ? 'tel' : field.type === 'DATE' ? 'date' : 'text';
  return (
    <label className="field" htmlFor={id}>
      {label}
      <input id={id} type={type} value={value} onChange={(e) => onChange(e.target.value)}
        maxLength={field.type === 'DATE' ? undefined : field.max}
        inputMode={field.type === 'YEAR' ? 'numeric' : undefined}
        placeholder={field.type === 'YEAR' ? '2019' : undefined}
        autoComplete={field.sensitive ? 'off' : undefined} />
    </label>
  );
}
