import { describe, it, expect, vi, beforeEach } from 'vitest';
import type {
  IConnectable,
  IAuthTransport,
  ICommandTransport,
  IEventTransport,
  ConnectionState,
  ConnectionStateHandler,
  EventHandler,
  TokenRequest,
  TokenResult,
} from '@kduma-autoid/hal-client-common';
import { HalClient } from '../hal-client.js';

function createMockAuthTransport(): IAuthTransport {
  return {
    requestToken: vi.fn<(request: TokenRequest) => Promise<TokenResult>>().mockResolvedValue({
      token: 'test-token',
      permissions: ['read', 'write'],
      expiresAt: Date.now() + 3600_000,
    }),
  };
}

function createMockCommandTransport(): ICommandTransport {
  return {
    setToken: vi.fn(),
    getToken: vi.fn().mockReturnValue(null),
    execute: vi.fn().mockResolvedValue({ result: 'ok' }),
    dispose: vi.fn(),
  };
}

function createMockEventTransport(): IEventTransport {
  return {
    setToken: vi.fn(),
    getToken: vi.fn().mockReturnValue(null),
    subscribe: vi.fn().mockResolvedValue(undefined),
    unsubscribe: vi.fn().mockResolvedValue(undefined),
    on: vi.fn<(pattern: string, handler: EventHandler) => () => void>().mockReturnValue(() => {}),
    off: vi.fn(),
    dispose: vi.fn(),
  };
}

function createMockConnection(): IConnectable {
  return {
    connect: vi.fn().mockResolvedValue(undefined),
    disconnect: vi.fn(),
    connectionState: 'disconnected' as ConnectionState,
    onConnectionStateChange: vi.fn<(handler: ConnectionStateHandler) => () => void>().mockReturnValue(() => {}),
  };
}

describe('HalClient', () => {
  let authTransport: IAuthTransport;
  let commandTransport: ICommandTransport;
  let eventTransport: IEventTransport;
  let connection: IConnectable;

  beforeEach(() => {
    authTransport = createMockAuthTransport();
    commandTransport = createMockCommandTransport();
    eventTransport = createMockEventTransport();
    connection = createMockConnection();
  });

  describe('wiring', () => {
    it('should return this for chaining', () => {
      const client = new HalClient({ clientId: 'test' });
      const result = client
        .useConnection(connection)
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport)
        .useEventTransport(eventTransport);

      expect(result).toBe(client);
    });
  });

  describe('execute', () => {
    it('should delegate to command transport', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      await client.requestToken();
      const result = await client.execute('test.method', { foo: 'bar' });

      expect(commandTransport.execute).toHaveBeenCalledWith('test.method', { foo: 'bar' }, undefined);
      expect(result).toEqual({ result: 'ok' });
    });

    it('should throw when no command transport configured', async () => {
      const client = new HalClient({ clientId: 'test' });

      await expect(client.execute('test.method')).rejects.toThrow(
        'No command transport configured. Call useCommandTransport() first.',
      );
    });

    it('should call ensureValidToken before executing', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      // No token set, no service key, should throw
      await expect(client.execute('test.method')).rejects.toThrow(
        'Token is expired',
      );

      expect(commandTransport.execute).not.toHaveBeenCalled();
    });
  });

  describe('execute onMeta option', () => {
    it('forwards the onMeta option to the transport and surfaces provider meta', async () => {
      (commandTransport.execute as ReturnType<typeof vi.fn>).mockImplementation(
        (_method: string, _params: unknown, options?: { onMeta?: (m: unknown) => void }) => {
          options?.onMeta?.({ provider: 'demo.beta' });
          return Promise.resolve({ ok: true });
        },
      );
      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      await client.requestToken();
      let seen: unknown;
      const result = await client.execute('demo.emit', { message: 'hi' }, {
        onMeta: (m) => { seen = m; },
      });

      expect(result).toEqual({ ok: true });
      expect(seen).toEqual({ provider: 'demo.beta' });
      expect(commandTransport.execute).toHaveBeenCalledWith(
        'demo.emit',
        { message: 'hi' },
        expect.objectContaining({ onMeta: expect.any(Function) }),
      );
    });

    it('forwards undefined options to the transport when none are given', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      await client.requestToken();
      await client.execute('test.method', { foo: 'bar' });

      expect(commandTransport.execute).toHaveBeenCalledWith('test.method', { foo: 'bar' }, undefined);
    });
  });

  describe('auth', () => {
    it('should request and store token', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      const result = await client.requestToken();

      expect(result.token).toBe('test-token');
      expect(client.isAuthenticated).toBe(true);
      expect(client.token).toBe('test-token');
      expect(client.permissions).toEqual(['read', 'write']);
    });

    it('should authenticate with existing token', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useCommandTransport(commandTransport);

      await client.authenticate('existing-token');

      expect(client.token).toBe('existing-token');
      expect(commandTransport.setToken).toHaveBeenCalledWith('existing-token');
    });

    it('should merge requestToken with options defaults', async () => {
      const client = new HalClient({
        clientId: 'my-client',
        serviceKey: 'my-key',
        requestedPermissions: ['read'],
      }).useAuthTransport(authTransport);

      await client.requestToken();

      expect(authTransport.requestToken).toHaveBeenCalledWith({
        clientId: 'my-client',
        serviceKey: 'my-key',
        requestedPermissions: ['read'],
      });
    });
  });

  describe('connection', () => {
    it('should delegate connect to connection', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useConnection(connection);

      await client.connect();
      expect(connection.connect).toHaveBeenCalled();
    });

    it('should delegate disconnect to connection', () => {
      const client = new HalClient({ clientId: 'test' })
        .useConnection(connection);

      client.disconnect(true);
      expect(connection.disconnect).toHaveBeenCalledWith(true);
    });

    it('should throw when no connection configured for connect', async () => {
      const client = new HalClient({ clientId: 'test' });

      await expect(client.connect()).rejects.toThrow(
        'No connection configured. Call useConnection() first.',
      );
    });

    it('should throw when no connection configured for disconnect', () => {
      const client = new HalClient({ clientId: 'test' });

      expect(() => client.disconnect()).toThrow(
        'No connection configured. Call useConnection() first.',
      );
    });

    it('should return disconnected when no connection', () => {
      const client = new HalClient({ clientId: 'test' });
      expect(client.connectionState).toBe('disconnected');
    });
  });

  describe('events (IEventSubscriber)', () => {
    it('should subscribe and register handler via on()', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useEventTransport(eventTransport);

      const handler = vi.fn();
      await client.on('printer.status', handler);

      expect(eventTransport.subscribe).toHaveBeenCalledWith(['printer.status']);
      expect(eventTransport.on).toHaveBeenCalledWith('printer.status', handler);
    });

    it('should return async unsubscribe function', async () => {
      const offFn = vi.fn();
      (eventTransport.on as ReturnType<typeof vi.fn>).mockReturnValue(offFn);

      const client = new HalClient({ clientId: 'test' })
        .useEventTransport(eventTransport);

      const unsub = await client.on('printer.status', vi.fn());
      await unsub();

      expect(offFn).toHaveBeenCalled();
      expect(eventTransport.unsubscribe).toHaveBeenCalledWith(['printer.status']);
    });

    it('should throw when no event transport configured', async () => {
      const client = new HalClient({ clientId: 'test' });

      await expect(client.on('test', vi.fn())).rejects.toThrow(
        'No event transport configured. Call useEventTransport() first.',
      );
    });
  });

  describe('dispose', () => {
    it('should cascade dispose to all components', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useConnection(connection)
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport)
        .useEventTransport(eventTransport);

      await client.requestToken();

      client.dispose();

      expect(eventTransport.dispose).toHaveBeenCalled();
      expect(commandTransport.dispose).toHaveBeenCalled();
      expect(connection.disconnect).toHaveBeenCalledWith(true);
    });

    it('should nullify references so subsequent calls throw', async () => {
      const client = new HalClient({ clientId: 'test' })
        .useConnection(connection)
        .useCommandTransport(commandTransport)
        .useEventTransport(eventTransport);

      client.dispose();

      await expect(client.execute('test')).rejects.toThrow(
        'No command transport configured',
      );
      await expect(client.on('test', vi.fn())).rejects.toThrow(
        'No event transport configured',
      );
      await expect(client.connect()).rejects.toThrow(
        'No connection configured',
      );
    });
  });

  describe('system convenience methods', () => {
    it('should call execute with system.ping for getHealth', async () => {
      const healthResponse = { pong: true, timestamp: Date.now() };
      (commandTransport.execute as ReturnType<typeof vi.fn>).mockResolvedValue(healthResponse);

      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      await client.requestToken();
      const result = await client.getHealth();

      expect(commandTransport.execute).toHaveBeenCalledWith('system.ping', undefined, undefined);
      expect(result).toEqual(healthResponse);
    });

    it('should call execute with system.status for getStatus', async () => {
      const statusResponse = { uptime: 1000, plugins: {}, transports: {} };
      (commandTransport.execute as ReturnType<typeof vi.fn>).mockResolvedValue(statusResponse);

      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      await client.requestToken();
      const result = await client.getStatus();

      expect(commandTransport.execute).toHaveBeenCalledWith('system.status', undefined, undefined);
      expect(result).toEqual(statusResponse);
    });

    it('should call execute with system.describe for getDescribe', async () => {
      const describeResponse = { plugins: [] };
      (commandTransport.execute as ReturnType<typeof vi.fn>).mockResolvedValue(describeResponse);

      const client = new HalClient({ clientId: 'test' })
        .useAuthTransport(authTransport)
        .useCommandTransport(commandTransport);

      await client.requestToken();
      const result = await client.getDescribe({ withSuper: true });

      expect(commandTransport.execute).toHaveBeenCalledWith('system.describe', { withSuper: true }, undefined);
      expect(result).toEqual(describeResponse);
    });
  });
});
