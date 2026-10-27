import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { DashboardPage } from './pages/DashboardPage';
import { ExceptionDetailPage } from './pages/ExceptionDetailPage';
import { ExceptionsPage } from './pages/ExceptionsPage';
import { LoginPage } from './pages/LoginPage';
import { SimulatorPage } from './pages/SimulatorPage';
import type { ReactNode } from 'react';
import type { Role } from './api/types';

function RequireAuth({ children, roles }: { children: ReactNode; roles?: Role[] }) {
  const { session, can } = useAuth();
  if (!session) return <Navigate to="/login" replace />;
  if (roles && !can(...roles)) return <p className="page">Your role ({session.role}) cannot open this page.</p>;
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/exceptions" element={<ExceptionsPage />} />
        <Route path="/exceptions/:id" element={<ExceptionDetailPage />} />
        <Route path="/simulator" element={<RequireAuth roles={['ADMIN']}><SimulatorPage /></RequireAuth>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
