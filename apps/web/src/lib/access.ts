import { ApiError, DATA_SCOPES, type DataScope, type Me } from '@hrms/api-client';
import { useSession } from './api';
import i18n from '../i18n';

/**
 * What the signed-in user may see. This only decides what the UI shows; the API checks every
 * request on its own (permission and data scope), so hiding a button is never the security boundary.
 */
export function can(user: Me | null, permission: string, atLeast: DataScope = 'SELF'): boolean {
  const scope = user?.scopes[permission];
  return !!scope && DATA_SCOPES.indexOf(scope) >= DATA_SCOPES.indexOf(atLeast);
}

export function canAny(user: Me | null, permissions: string[]): boolean {
  return permissions.some((p) => can(user, p));
}

export function hasModule(user: Me | null, module: string): boolean {
  return !!user?.modules.includes(module);
}

export function useAccess() {
  const session = useSession();
  const user = session.user;
  return {
    user,
    can: (permission: string, atLeast?: DataScope) => can(user, permission, atLeast),
    canAny: (permissions: string[]) => canAny(user, permissions),
    hasModule: (module: string) => hasModule(user, module),
  };
}

/** Data scope names in the current language (read at render time, so a language switch updates them). */
export const SCOPE_LABEL = new Proxy({} as Record<DataScope, string>, {
  get: (_, scope: string) => i18n.t(`scope.${scope as DataScope}`),
});

/** Plain-language error for a failed API call: the API's own message, or what to do when it's unreachable. */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.problem.status >= 500) return i18n.t('errors.server');
    return error.message;
  }
  return i18n.t('errors.offline');
}
