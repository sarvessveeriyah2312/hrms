import type { IconName } from '../components/Icon';
import type { Tone } from '../components/Glyph';
import i18n from '../i18n';

/** One icon and colour per module, used the same way in menus, tiles, rows and the role editor. */
type ModuleKey = 'attendance' | 'leave' | 'claims' | 'employee' | 'reports' | 'payroll' | 'comms' | 'workflow' | 'admin';
const meta = (key: ModuleKey, icon: IconName, tone: Tone) => ({ icon, tone, get label() { return i18n.t(`module.${key}`); } });

export const MODULE_META: Record<string, { icon: IconName; tone: Tone; label: string }> = {
  attendance: meta('attendance', 'clock', 'blue'),
  leave: meta('leave', 'calendar', 'teal'),
  claims: meta('claims', 'receipt', 'orange'),
  employee: meta('employee', 'users', 'green'),
  reports: meta('reports', 'chart', 'purple'),
  payroll: meta('payroll', 'wallet', 'pink'),
  comms: meta('comms', 'megaphone', 'orange'),
  workflow: meta('workflow', 'switch', 'blue'),
  admin: meta('admin', 'sliders', 'pink'),
};

export function moduleMeta(key: string) {
  return MODULE_META[key] ?? { icon: 'file' as IconName, tone: 'blue' as Tone, label: key };
}

// The permission catalog and built-in roles come from the API in English. Show them in the current language
// when there's a translation; anything an admin typed (custom roles, renamed roles) is shown as typed.
const tr = (key: string, fallback: string) => (i18n.exists(key) ? (i18n.t as (k: string) => string)(key) : fallback);

export function moduleName(key: string, fallback: string) {
  return tr(`module.${key}`, fallback);
}

export function permText(module: string, action: string, fallback: { name: string; description: string }) {
  return {
    name: tr(`perm.${module}.${action}.name`, fallback.name),
    description: tr(`perm.${module}.${action}.description`, fallback.description),
  };
}

const BUILT_IN_EN: Record<string, string> = { SUPER_ADMIN: 'Super admin', HR_ADMIN: 'HR admin', MANAGER: 'Manager', EMPLOYEE: 'Employee' };

/** A role's name and description; built-in roles still under their original name are translated. */
export function roleText(role: { code?: string | null; name: string; description?: string | null; system?: boolean }) {
  const code = role.code ?? '';
  if (role.system && BUILT_IN_EN[code] === role.name) {
    return { name: tr(`roleNames.${code}.name`, role.name), description: tr(`roleNames.${code}.description`, role.description ?? '') };
  }
  return { name: role.name, description: role.description ?? '' };
}

/** Role names as listed on a user (names only): translate the built-in ones. */
export function roleLabel(name: string) {
  const code = Object.keys(BUILT_IN_EN).find((c) => BUILT_IN_EN[c] === name);
  return code ? tr(`roleNames.${code}.name`, name) : name;
}
