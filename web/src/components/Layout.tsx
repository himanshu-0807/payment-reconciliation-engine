import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { humanize } from '../format';

export function Layout() {
  const { session, logout, can } = useAuth();
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">Recon<span>Engine</span></div>
        <nav>
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/exceptions">Exceptions</NavLink>
          {can('ADMIN') && <NavLink to="/simulator">Simulator</NavLink>}
        </nav>
        <div className="user">
          <span title={session?.role}>{session?.username} · {humanize(session?.role)}</span>
          <button className="link" onClick={logout}>Sign out</button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
