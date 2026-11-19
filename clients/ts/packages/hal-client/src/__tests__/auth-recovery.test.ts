import { describe, it, expect, vi } from 'vitest';
import type { IAuthTransport, ICommandTransport, TokenRequest, TokenResult, AuthState } from '@kduma-autoid/hal-client-common';
import { HalError, InMemoryTokenStore } from '@kduma-autoid/hal-client-common';
import { HttpCommandTransport } from '@kduma-autoid/hal-client-transport-http';
import { HalClient } from '../hal-client.js';
import type { HalClientOptions } from '../hal-client-options.js';

const unauthorized = () => new HalError('unauthorized', 'Invalid token');

function setup(options: Partial<HalClientOptions> = {}, tokens: string[] = ['t1', 't2', 't3']) {
  let issued = 0;
  const auth: IAuthTransport = {
    requestToken: vi.fn(async (_req: TokenRequest): Promise<TokenResult> => ({
      token: tokens[issued++] ?? `t${issued}`,
      permissions: ['*'],
      expiresAt: Date.now() + 3_600_000,
    })),
  };
  let rejected: ((token: string) => void) | null = null;
  const commands = {
    token: null as string | null,
    setToken: vi.fn(function (this: { token: string | null }, t: string) {
      commands.token = t;
    }),
    getToken: () => commands.token,
    execute: vi.fn(),
    onTokenRejected: vi.fn((h: (token: string) => void) => {
      rejected = h;
      return () => undefined;
    }),
    dispose: vi.fn(),
  };
  const client = new HalClient({ clientId: 'app', tokenStore: new InMemoryTokenStore(), ...options })
    .useAuthTransport(auth)
    .useCommandTransport(commands as unknown as ICommandTransport);
  const states: AuthState[] = [];
  client.onAuthStateChange((s) => states.push(s));
  return { client, auth, commands, states, reject: (t: string) => rejected?.(t) };
}

describe('auth recovery', () => {
  it('with a service key: renews a rejected token and retries the command once', async () => {
    const { client, auth, commands, states } = setup({ serviceKey: 'key' });
    await client.requestToken();
    commands.execute.mockRejectedValueOnce(unauthorized()).mockResolvedValueOnce({ ok: true });

    await expect(client.execute('x.y')).resolves.toEqual({ ok: true });

    expect(commands.execute).toHaveBeenCalledTimes(2);
    expect(auth.requestToken).toHaveBeenCalledTimes(2);
    expect(client.token).toBe('t2');
    expect(states).toEqual(['authenticated', 'refreshing', 'authenticated']);
  });

  it('retries only once: a second rejection propagates', async () => {
    const { client, commands } = setup({ serviceKey: 'key' });
    await client.requestToken();
    commands.execute.mockRejectedValue(unauthorized());
    await expect(client.execute('x.y')).rejects.toThrow('Invalid token');
    expect(commands.execute).toHaveBeenCalledTimes(2);
  });

  it('calls a service-key function for every token request', async () => {
    let n = 0;
    const serviceKey = vi.fn(async () => `jwt-${++n}`);
    const { client, auth, commands } = setup({ serviceKey });
    await client.requestToken();
    commands.execute.mockRejectedValueOnce(unauthorized()).mockResolvedValueOnce({});
    await client.execute('x.y');

    expect(serviceKey).toHaveBeenCalledTimes(2);
    expect(vi.mocked(auth.requestToken).mock.calls.map((c) => c[0].serviceKey)).toEqual(['jwt-1', 'jwt-2']);
  });

  it('without a service key: turns expired, notifies, and does not retry', async () => {
    const onTokenExpired = vi.fn();
    const { client, auth, commands, states } = setup({ onTokenExpired });
    await client.requestToken();
    commands.execute.mockRejectedValueOnce(unauthorized());

    await expect(client.execute('x.y')).rejects.toThrow('Invalid token');

    expect(commands.execute).toHaveBeenCalledTimes(1);
    expect(auth.requestToken).toHaveBeenCalledTimes(1);
    expect(onTokenExpired).toHaveBeenCalledTimes(1);
    expect(client.authState).toBe('expired');
    expect(client.isAuthenticated).toBe(false);
    expect(states).toEqual(['authenticated', 'expired']);

    // The app asks for a new token (the dialog): back to authenticated.
    await client.requestToken();
    expect(client.authState).toBe('authenticated');
  });

  it('autoRequestToken renews without a service key', async () => {
    const { client, commands } = setup({ autoRequestToken: true });
    await client.requestToken();
    commands.execute.mockRejectedValueOnce(unauthorized()).mockResolvedValueOnce({ ok: 1 });
    await expect(client.execute('x.y')).resolves.toEqual({ ok: 1 });
    expect(client.token).toBe('t2');
  });

  it('a refused token request sets denied', async () => {
    const { client, auth } = setup();
    vi.mocked(auth.requestToken).mockRejectedValueOnce(new HalError('user_denied', 'User denied permission'));
    await expect(client.requestToken()).rejects.toThrow('User denied');
    expect(client.authState).toBe('denied');
  });

  it('concurrent rejections share one renewal', async () => {
    const { client, auth, commands } = setup({ serviceKey: 'key' });
    await client.requestToken();
    commands.execute
      .mockRejectedValueOnce(unauthorized())
      .mockRejectedValueOnce(unauthorized())
      .mockResolvedValue({});
    await Promise.all([client.execute('a'), client.execute('b')]);
    expect(auth.requestToken).toHaveBeenCalledTimes(2); // initial + one renewal
  });

  it('a token rejected by the transport itself (WS re-authenticate) is renewed', async () => {
    const { client, auth, reject } = setup({ serviceKey: 'key' });
    await client.requestToken();
    reject('t1');
    await vi.waitFor(() => expect(auth.requestToken).toHaveBeenCalledTimes(2));
    expect(client.token).toBe('t2');
  });

  it('gives up instead of looping when the renewed token is rejected right away again', async () => {
    const { client, auth, reject } = setup({ serviceKey: 'key' }, ['same', 'same', 'same']);
    await client.requestToken();
    reject('same');
    await vi.waitFor(() => expect(client.authState).toBe('authenticated'));
    reject('same');
    await vi.waitFor(() => expect(client.authState).toBe('expired'));
    expect(auth.requestToken).toHaveBeenCalledTimes(2);
  });

  it('renews a service-key token right after it expires', async () => {
    vi.useFakeTimers();
    try {
      const { client, auth } = setup({ serviceKey: 'key' });
      vi.mocked(auth.requestToken).mockResolvedValueOnce({ token: 'short', permissions: [], expiresAt: Date.now() + 10_000 });
      await client.requestToken();
      await vi.advanceTimersByTimeAsync(11_500);
      expect(auth.requestToken).toHaveBeenCalledTimes(2);
      expect(client.token).not.toBe('short');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('auth recovery over HTTP', () => {
  function httpClient(options: Partial<HalClientOptions>) {
    const responses: Array<{ status: number; body: unknown }> = [];
    const requests: Array<{ url: string; headers: Record<string, string> }> = [];
    const http = new HttpCommandTransport({
      baseUrl: 'http://hal',
      httpAdapter: {
        async request(o) {
          requests.push({ url: o.url, headers: o.headers ?? {} });
          const next = responses.shift() ?? { status: 200, body: {} };
          return { status: next.status, statusText: '', headers: {}, body: JSON.stringify(next.body) };
        },
      },
    });
    const client = new HalClient({ clientId: 'app', ...options }).useAuthTransport(http).useCommandTransport(http);
    return { client, responses, requests };
  }

  it('a 401 unauthorized renews the token (service key) and retries', async () => {
    const { client, responses, requests } = httpClient({ serviceKey: 'key' });
    responses.push({ status: 200, body: { token: 'h1', permissions: ['*'], expires_at: Date.now() + 3_600_000 } });
    await client.requestToken();

    responses.push(
      { status: 401, body: { error: 'unauthorized', message: 'Invalid token' } },
      { status: 200, body: { token: 'h2', permissions: ['*'], expires_at: Date.now() + 3_600_000 } },
      { status: 200, body: { pong: true } },
    );
    await expect(client.execute('system.ping')).resolves.toEqual({ pong: true });
    expect(requests.map((r) => r.url.replace('http://hal', ''))).toEqual(['/api/token', '/api/execute', '/api/token', '/api/execute']);
    expect(requests[3].headers.Authorization).toBe('Bearer h2');
  });

  it('a 401 without a service key ends in expired', async () => {
    const { client, responses } = httpClient({});
    responses.push({ status: 200, body: { token: 'h1', permissions: ['*'] } });
    await client.requestToken();
    responses.push({ status: 401, body: { error: 'unauthorized', message: 'Invalid token' } });
    await expect(client.execute('system.ping')).rejects.toMatchObject({ code: 'unauthorized' });
    expect(client.authState).toBe('expired');
  });
});
