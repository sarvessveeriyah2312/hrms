import { useTranslation } from 'react-i18next';
import { LANGUAGES, setLanguage, type Language } from '../i18n';

/** Picks the interface language. Each option is written in its own language so anyone can find theirs. */
export function LanguageSwitcher({ className }: { className?: string }) {
  const { t, i18n } = useTranslation();
  return (
    <label className={['ui-lang', className].filter(Boolean).join(' ')} title={t('common.language')}>
      <span className="sr-only">{t('common.language')}</span>
      <select value={i18n.language} onChange={(e) => setLanguage(e.target.value as Language)}>
        {LANGUAGES.map((l) => (
          <option key={l.code} value={l.code} lang={l.html}>{l.label}</option>
        ))}
      </select>
      <span className="ui-lang__short" aria-hidden="true">{LANGUAGES.find((l) => l.code === i18n.language)?.short ?? 'EN'}</span>
    </label>
  );
}
