import { ApiError, createApi, query } from './client';

function response(status: number, body?: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    statusText: 'status ' + status,
    text: () => Promise.resolve(body === undefined ? '' : JSON.stringify(body)),
  } as Response;
}

describe('api client', () => {
  it('sends the bearer token and JSON body', async () => {
    const fetch = jest.fn().mockResolvedValue(response(200, { id: 7 }));
    const api = createApi({ fetch, getToken: () => 'abc' });
    await api.assign(7, 'alice');
    const [url, init] = fetch.mock.calls[0];
    expect(url).toBe('/api/exceptions/7/assign');
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe('Bearer abc');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body)).toEqual({ assignee: 'alice' });
  });

  it('omits the Authorization header without a token', async () => {
    const fetch = jest.fn().mockResolvedValue(response(200, { token: 't', role: 'ADMIN' }));
    await createApi({ fetch, getToken: () => null }).login('u', 'p');
    expect(fetch.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it('turns problem+json into ApiError with code and field errors', async () => {
    const fetch = jest.fn().mockResolvedValue(response(400, {
      status: 400, code: 'VALIDATION_FAILED', detail: 'request validation failed',
      errors: [{ field: 'resolutionReason', message: 'must be one of ...' }],
    }));
    const api = createApi({ fetch, getToken: () => 't' });
    const err = await api.resolve(1, 'X', '').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.problem.code).toBe('VALIDATION_FAILED');
    expect(err.problem.errors[0].field).toBe('resolutionReason');
  });

  it('calls onUnauthorized on 401 (but not for a failed login)', async () => {
    const onUnauthorized = jest.fn();
    const fetch = jest.fn().mockResolvedValue(response(401, { code: 'UNAUTHENTICATED', detail: 'expired' }));
    const api = createApi({ fetch, getToken: () => 't', onUnauthorized });
    await expect(api.dashboard()).rejects.toThrow('expired');
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    await expect(api.login('a', 'b')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('reports network failures as NETWORK_ERROR', async () => {
    const fetch = jest.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const err = await createApi({ fetch, getToken: () => null }).trend().catch((e) => e);
    expect(err.problem).toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });

  it('builds query strings from filters, skipping empty values', async () => {
    const fetch = jest.fn().mockResolvedValue(response(200, { content: [] }));
    await createApi({ fetch, getToken: () => 't' }).exceptions({ status: 'OPEN,IN_REVIEW', merchantId: '', minAmountMinor: 150000, page: 2 });
    expect(fetch.mock.calls[0][0]).toBe('/api/exceptions?status=OPEN%2CIN_REVIEW&minAmountMinor=150000&page=2');
    expect(query({})).toBe('');
  });

  it('handles 204 No Content', async () => {
    const fetch = jest.fn().mockResolvedValue(response(204));
    await expect(createApi({ fetch, getToken: () => 't' }).approveAdjustment(3)).resolves.toBeUndefined();
  });
});
