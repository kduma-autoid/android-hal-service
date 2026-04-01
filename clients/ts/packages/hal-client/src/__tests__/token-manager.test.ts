import { describe, it, expect, vi, beforeEach } from 'vitest';
import type {
  ITokenStore,
  IAuthTransport,
  ICommandTransport,
  IEventTransport,
  TokenRequest,
  TokenResult,
  EventHandler,
} from '@kduma-autoid/hal-client-common';
import { TokenManager } from '../token-manager.js';
import { InMemoryTokenStore } from '@kduma-autoid/hal-client-common';

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
    execute: vi.fn().mockResolvedValue({}),
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

describe('TokenManager', () => {
  let tokenStore: ITokenStore;
  let authTransport: IAuthTransport;
  let commandTransport: ICommandTransport;
  let eventTransport: IEventTransport;

  beforeEach(() => {
    tokenStore = new InMemoryTokenStore();
    authTransport = createMockAuthTransport();
    commandTransport = createMockCommandTransport();
    eventTransport = createMockEventTransport();
  });

  describe('requestToken', () => {
    it('should store and propagate token', async () => {
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        commandTransport,
        eventTransport,
        { clientId: 'test-client' },
      );

      const result = await manager.requestToken();

      expect(result.token).toBe('test-token');
      expect(result.permissions).toEqual(['read', 'write']);
      expect(tokenStore.getToken()).toBe('test-token');
      expect(commandTransport.setToken).toHaveBeenCalledWith('test-token');
      expect(eventTransport.setToken).toHaveBeenCalledWith('test-token');
    });

    it('should throw if no auth transport is configured', async () => {
      const manager = new TokenManager(tokenStore);

      await expect(manager.requestToken()).rejects.toThrow(
        'No auth transport configured',
      );
    });

    it('should merge request with options defaults', async () => {
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        undefined,
        undefined,
        {
          clientId: 'default-client',
          serviceKey: 'dev-key',
          requestedPermissions: ['read'],
        },
      );

      await manager.requestToken();

      expect(authTransport.requestToken).toHaveBeenCalledWith({
        clientId: 'default-client',
        serviceKey: 'dev-key',
        requestedPermissions: ['read'],
      });
    });

    it('should allow overriding defaults in request', async () => {
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        undefined,
        undefined,
        {
          clientId: 'default-client',
          serviceKey: 'dev-key',
        },
      );

      await manager.requestToken({
        clientId: 'override-client',
        requestedPermissions: ['admin'],
      });

      expect(authTransport.requestToken).toHaveBeenCalledWith({
        clientId: 'override-client',
        serviceKey: 'dev-key',
        requestedPermissions: ['admin'],
      });
    });
  });

  describe('thundering herd prevention', () => {
    it('should reuse in-flight refresh promise for concurrent calls', async () => {
      let resolveToken: ((value: TokenResult) => void) | null = null;
      const slowAuth: IAuthTransport = {
        requestToken: vi.fn<(request: TokenRequest) => Promise<TokenResult>>().mockImplementation(
          () =>
            new Promise<TokenResult>((resolve) => {
              resolveToken = resolve;
            }),
        ),
      };

      // Set an expired token first
      tokenStore.setToken({
        token: 'expired-token',
        permissions: [],
        expiresAt: Date.now() - 1000,
      });

      const manager = new TokenManager(
        tokenStore,
        slowAuth,
        commandTransport,
        eventTransport,
        { clientId: 'test', serviceKey: 'key' },
      );

      // Fire off two concurrent ensureValidToken calls
      const p1 = manager.ensureValidToken();
      const p2 = manager.ensureValidToken();

      // Only one requestToken call should have been made
      expect(slowAuth.requestToken).toHaveBeenCalledTimes(1);

      // Resolve the token
      resolveToken!({
        token: 'new-token',
        permissions: ['read'],
        expiresAt: Date.now() + 3600_000,
      });

      await Promise.all([p1, p2]);

      // Still only one call
      expect(slowAuth.requestToken).toHaveBeenCalledTimes(1);
      expect(tokenStore.getToken()).toBe('new-token');
    });
  });

  describe('ensureValidToken', () => {
    it('should not refresh if token is valid', async () => {
      tokenStore.setToken({
        token: 'valid-token',
        permissions: ['read'],
        expiresAt: Date.now() + 3600_000,
      });

      const manager = new TokenManager(
        tokenStore,
        authTransport,
        commandTransport,
        eventTransport,
        { clientId: 'test', serviceKey: 'key' },
      );

      await manager.ensureValidToken();
      expect(authTransport.requestToken).not.toHaveBeenCalled();
    });

    it('should auto-refresh if expired and serviceKey is present', async () => {
      tokenStore.setToken({
        token: 'expired-token',
        permissions: [],
        expiresAt: Date.now() - 1000,
      });

      const manager = new TokenManager(
        tokenStore,
        authTransport,
        commandTransport,
        eventTransport,
        { clientId: 'test', serviceKey: 'key' },
      );

      await manager.ensureValidToken();
      expect(authTransport.requestToken).toHaveBeenCalledTimes(1);
      expect(tokenStore.getToken()).toBe('test-token');
    });

    it('should call onTokenExpired and throw if expired without serviceKey', async () => {
      tokenStore.setToken({
        token: 'expired-token',
        permissions: [],
        expiresAt: Date.now() - 1000,
      });

      const onTokenExpired = vi.fn();
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        commandTransport,
        eventTransport,
        { clientId: 'test', onTokenExpired },
      );

      await expect(manager.ensureValidToken()).rejects.toThrow(
        'Token is expired and cannot be auto-refreshed',
      );
      expect(onTokenExpired).toHaveBeenCalledTimes(1);
    });

    it('should throw if no token is set', async () => {
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        commandTransport,
        eventTransport,
        { clientId: 'test' },
      );

      await expect(manager.ensureValidToken()).rejects.toThrow(
        'Token is expired and cannot be auto-refreshed',
      );
    });
  });

  describe('clearToken', () => {
    it('should clear token from store', async () => {
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        commandTransport,
        eventTransport,
        { clientId: 'test' },
      );

      await manager.requestToken();
      expect(tokenStore.getToken()).toBe('test-token');

      manager.clearToken();
      expect(tokenStore.getToken()).toBeNull();
    });
  });

  describe('authenticateWithExistingToken', () => {
    it('should set token on store and propagate', () => {
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        commandTransport,
        eventTransport,
      );

      manager.authenticateWithExistingToken('existing-token');

      expect(tokenStore.getToken()).toBe('existing-token');
      expect(commandTransport.setToken).toHaveBeenCalledWith('existing-token');
      expect(eventTransport.setToken).toHaveBeenCalledWith('existing-token');
    });
  });

  describe('isAuthenticated', () => {
    it('should return false when no token', () => {
      const manager = new TokenManager(tokenStore);
      expect(manager.isAuthenticated()).toBe(false);
    });

    it('should return true when valid token exists', async () => {
      const manager = new TokenManager(
        tokenStore,
        authTransport,
        undefined,
        undefined,
        { clientId: 'test' },
      );
      await manager.requestToken();
      expect(manager.isAuthenticated()).toBe(true);
    });
  });

  describe('registerTransport', () => {
    it('should allow registering transports after construction', async () => {
      const manager = new TokenManager(tokenStore);

      manager.registerAuthTransport(authTransport);
      manager.registerCommandTransport(commandTransport);
      manager.registerEventTransport(eventTransport);

      const result = await manager.requestToken({ clientId: 'test' });
      expect(result.token).toBe('test-token');
      expect(commandTransport.setToken).toHaveBeenCalledWith('test-token');
      expect(eventTransport.setToken).toHaveBeenCalledWith('test-token');
    });
  });
});
