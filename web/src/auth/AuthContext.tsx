import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { createApi, type Api } from '../api/client';
import type { Role } from '../api/types';

export interface Session {
  token: string;
  username: string;
  role: Role;
}

interface AuthValue {
  session: Session | null;
  api: Api;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  can: (...roles: Role[]) => boolean;
}

const AuthContext = createContext<AuthValue | null>(null);
const KEY = 'recon.session';

function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

function writeSession(s: Session | null) {
  try {
    if (s) sessionStorage.setItem(KEY, JSON.stringify(s));
    else sessionStorage.removeItem(KEY);
  } catch {
    // storage unavailable (private mode): the session just lasts for this page
  }
}

/** Holds the JWT session and exposes an API client bound to it. `apiFactory` lets tests inject a fake API. */
export function AuthProvider({ children, apiFactory }: { children: ReactNode; apiFactory?: typeof createApi }) {
  const [session, setSession] = useState<Session | null>(readSession);
  const logout = useCallback(() => {
    writeSession(null);
    setSession(null);
  }, []);
  const api = useMemo(
    () => (apiFactory ?? createApi)({ getToken: () => session?.token ?? null, onUnauthorized: logout }),
    [apiFactory, logout, session],
  );

  const login = useCallback(async (username: string, password: string) => {
    const r = await api.login(username, password);
    const s: Session = { token: r.token, username: r.username, role: r.role };
    writeSession(s);
    setSession(s);
  }, [api]);

  const can = useCallback((...roles: Role[]) => !!session && roles.includes(session.role), [session]);

  return <AuthContext.Provider value={{ session, api, login, logout, can }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const v = useContext(AuthContext);
  if (!v) throw new Error('useAuth must be used inside <AuthProvider>');
  return v;
}
