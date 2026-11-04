import type { IconName } from '../../components/Icon';
import type { Tone } from '../../components/Glyph';

// Landing structure. The words live in the translation files under `landing.*`.

export type Feature<K extends string> = { key: K; icon: IconName; tone: Tone };

export const FOR_EMPLOYEES: Feature<'clock' | 'leave' | 'claims' | 'payslips' | 'hr'>[] = [
  { key: 'clock', icon: 'clock', tone: 'blue' },
  { key: 'leave', icon: 'calendar', tone: 'teal' },
  { key: 'claims', icon: 'receipt', tone: 'orange' },
  { key: 'payslips', icon: 'file', tone: 'purple' },
  { key: 'hr', icon: 'message', tone: 'pink' },
];

export const FOR_MANAGERS: Feature<'approve' | 'whosIn' | 'shifts' | 'rules' | 'payroll'>[] = [
  { key: 'approve', icon: 'inbox', tone: 'blue' },
  { key: 'whosIn', icon: 'users', tone: 'green' },
  { key: 'shifts', icon: 'calendar', tone: 'teal' },
  { key: 'rules', icon: 'sliders', tone: 'purple' },
  { key: 'payroll', icon: 'chart', tone: 'orange' },
];

export const CLOCK_METHODS: Feature<'geofence' | 'wifi' | 'face' | 'kiosk'>[] = [
  { key: 'geofence', icon: 'pin', tone: 'blue' },
  { key: 'wifi', icon: 'wifi', tone: 'green' },
  { key: 'face', icon: 'face', tone: 'purple' },
  { key: 'kiosk', icon: 'tablet', tone: 'orange' },
];

export const APPROVAL_STEPS = ['submit', 'manager', 'head', 'hr'] as const;

export const COUNTRIES = ['SG', 'MY', 'HK', 'AU', 'NZ', 'IN'] as const;
