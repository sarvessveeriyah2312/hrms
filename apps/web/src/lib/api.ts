import { useSyncExternalStore } from 'react';
import { createApiClient, type Me, type Session } from '@hrms/api-client';

/**
 * API client and sign-in session for the web app.
 * The access token lives only in memory; the refresh token is an HttpOnly cookie set by the API
 * (never localStorage, so injected scripts cannot read it). A page reload restores the session
 * by calling /auth/refresh.
 */

export type SessionState =
  | { status: 'unknown'; user: null } // not checked yet (first load)
  | { status: 'signed-in'; user: Me }
  | { status: 'signed-out'; user: null };

let accessToken: string | null = null;
let state: SessionState = { status: 'unknown', user: null };
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let refreshing: Promise<boolean> | null = null;
const listeners = new Set<() => void>();

function setState(next: SessionState) {
  state = next;
  listeners.forEach((l) => l());
}

function start(session: Session) {
  accessToken = session.accessToken;
  setState({ status: 'signed-in', user: session.user });
  // Refresh a minute before the token expires so requests rarely hit a 401.
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => void refreshSession(), Math.max(session.expiresIn - 60, 10) * 1000);
}

function end() {
  accessToken = null;
  clearTimeout(refreshTimer);
  setState({ status: 'signed-out', user: null });
}

export const api = createApiClient({
  // Empty in development: Vite proxies /api to the API (see vite.config.ts).
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? '',
  getAccessToken: () => accessToken,
  refreshAccessToken: () => refreshSession(),
  onUnauthorized: () => end(),
});

/** Gets a new access token from the refresh cookie. Concurrent callers share one request. */
export function refreshSession(): Promise<boolean> {
  refreshing ??= api
    .refresh()
    .then((session) => {
      start(session);
      return true;
    })
    .catch(() => {
      end();
      return false;
    })
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

export async function signIn(username: string, password: string, companyCode?: string) {
  start(await api.login(username, password, companyCode));
}

/** Uses a session the API just returned (e.g. after changing your password). */
export function applySession(session: Session) {
  start(session);
}

/** Re-reads permissions after an admin change (they're baked into the access token). */
export function reloadSession() {
  return refreshSession();
}

export async function signOut() {
  try {
    await api.logout();
  } finally {
    end();
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useSession(): SessionState {
  return useSyncExternalStore(subscribe, () => state);
}
