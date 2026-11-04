import 'i18next';
import type { en } from './en';

// Keys passed to t() are checked against the English messages at compile time.
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation';
    resources: { translation: typeof en };
  }
}
