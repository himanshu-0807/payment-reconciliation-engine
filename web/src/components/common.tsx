import type { ReactNode } from 'react';
import { ApiError } from '../api/client';
import { humanize } from '../format';

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{humanize(status)}</span>;
}

export function ClassBadge({ value }: { value: string }) {
  return <span className={`chip chip-${value.toLowerCase()}`}>{humanize(value)}</span>;
}

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof ApiError
    ? `${error.problem.detail}${error.problem.errors?.length ? ': ' + error.problem.errors.map((e) => `${e.field} ${e.message}`).join(', ') : ''}`
    : (error as Error).message;
  return <div role="alert" className="error">{msg}</div>;
}

export function Card({ title, children, className = '' }: { title?: string; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {title && <h2>{title}</h2>}
      {children}
    </section>
  );
}

export function Pagination({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  return (
    <div className="pagination" aria-label="pagination">
      <button disabled={page <= 0} onClick={() => onPage(page - 1)}>Previous</button>
      <span>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</span>
      <button disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</button>
    </div>
  );
}
