import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { en } from './en';
import { ms } from './ms';
import { zh } from './zh';

// English, Bahasa Melayu and Simplified Chinese (BRD BR-04).
export type Language = 'en' | 'ms' | 'zh';

export const LANGUAGES: { code: Language; label: string; short: string; html: string; dates: string }[] = [
  { code: 'en', label: 'English', short: 'EN', html: 'en', dates: 'en-GB' },
  { code: 'ms', label: 'Bahasa Melayu', short: 'BM', html: 'ms', dates: 'ms-MY' },
  { code: 'zh', label: '简体中文', short: '中文', html: 'zh-Hans', dates: 'zh-CN' },
];

const STORAGE_KEY = 'hrms-lang';

function initial(): Language {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'en' || saved === 'ms' || saved === 'zh') return saved;
  } catch {
    // storage blocked: use the browser language
  }
  const nav = navigator.language.toLowerCase();
  return nav.startsWith('ms') ? 'ms' : nav.startsWith('zh') ? 'zh' : 'en';
}

function info(code: string) {
  return LANGUAGES.find((l) => l.code === code) ?? LANGUAGES[0];
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, ms: { translation: ms }, zh: { translation: zh } },
  lng: initial(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

document.documentElement.lang = info(i18n.language).html;
i18n.on('languageChanged', (code) => {
  document.documentElement.lang = info(code).html;
});

/** Switches the whole app and remembers the choice on this device. */
export function setLanguage(code: Language) {
  void i18n.changeLanguage(code);
  try {
    localStorage.setItem(STORAGE_KEY, code);
  } catch {
    // not saved; the choice still holds for this visit
  }
}

/** The locale for dates and numbers in the current language, e.g. "ms-MY". */
export function dateLocale(): string {
  return info(i18n.language).dates;
}

export default i18n;
