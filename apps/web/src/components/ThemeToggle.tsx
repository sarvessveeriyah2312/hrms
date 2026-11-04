import { useTranslation } from 'react-i18next';
import { toggleTheme, useTheme } from '../lib/theme';
import { Icon } from './Icon';

/** Switches between light and dark. The icon shows the mode you'll switch to; the accessible name says it in words. */
export function ThemeToggle() {
  const { t } = useTranslation();
  const theme = useTheme();
  const next = theme === 'dark' ? 'light' : 'dark';
  const label = next === 'dark' ? t('site.darkMode') : t('site.lightMode');

  return (
    <button
      type="button"
      className="ui-theme-toggle"
      onClick={toggleTheme}
      aria-label={label}
      title={label}
    >
      <Icon name={next === 'dark' ? 'moon' : 'sun'} size={18} />
    </button>
  );
}
