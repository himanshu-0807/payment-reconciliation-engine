import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../api/client';
import { fakeApi, renderPage } from '../test/render';
import { LoginPage } from './LoginPage';

describe('LoginPage', () => {
  it('signs in with the entered credentials and stores the session', async () => {
    const api = fakeApi({ login: jest.fn().mockResolvedValue({ token: 'jwt', username: 'supervisor', role: 'OPS_SUPERVISOR' }) });
    renderPage(<LoginPage />, { api, role: null });
    await userEvent.type(screen.getByLabelText('Username'), 'supervisor');
    await userEvent.type(screen.getByLabelText('Password'), 'secret');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(api.login).toHaveBeenCalledWith('supervisor', 'secret');
    await waitFor(() => expect(JSON.parse(sessionStorage.getItem('recon.session')!).role).toBe('OPS_SUPERVISOR'));
  });

  it('shows the server message on bad credentials', async () => {
    const api = fakeApi({ login: jest.fn().mockRejectedValue(new ApiError({ status: 401, code: 'BAD_CREDENTIALS', detail: 'invalid username or password' })) });
    renderPage(<LoginPage />, { api, role: null });
    await userEvent.type(screen.getByLabelText('Username'), 'x');
    await userEvent.type(screen.getByLabelText('Password'), 'y');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('invalid username or password');
    expect(sessionStorage.getItem('recon.session')).toBeNull();
  });
});
