import { useState, type FormEvent } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ErrorBanner } from '../components/common';

export function LoginPage() {
  const { login, session } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  if (session) return <Navigate to="/" replace />;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form onSubmit={submit} className="card login-card" aria-label="sign in">
        <h1>Payment reconciliation</h1>
        <p className="muted">Sign in with a demo user (analyst, supervisor or admin; passwords are in .env).</p>
        <label>Username<input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required /></label>
        <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required /></label>
        <ErrorBanner error={error} />
        <button type="submit" className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  );
}
