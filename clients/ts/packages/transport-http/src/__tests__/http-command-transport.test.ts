import { describe, it, expect, beforeEach } from 'vitest';
import { HalError } from '@kduma-autoid/hal-client-common';
import { HttpCommandTransport } from '../http-command-transport.js';
import type { IHttpAdapter, HttpRequestOptions, HttpResponse } from '../interfaces/http-adapter.js';

function createMockAdapter(): IHttpAdapter & {
  lastRequest: HttpRequestOptions | null;
  nextResponse: HttpResponse;
} {
  const mock = {
    lastRequest: null as HttpRequestOptions | null,
    nextResponse: {
      status: 200,
      statusText: 'OK',
      headers: {},
      body: '{}',
    } as HttpResponse,
    async request(options: HttpRequestOptions): Promise<HttpResponse> {
      mock.lastRequest = options;
      return mock.nextResponse;
    },
  };
  return mock;
}

describe('HttpCommandTransport', () => {
  let adapter: ReturnType<typeof createMockAdapter>;
  let transport: HttpCommandTransport;

  beforeEach(() => {
    adapter = createMockAdapter();
    transport = new HttpCommandTransport({
      baseUrl: 'http://localhost:8080',
      httpAdapter: adapter,
    });
  });

  describe('requestToken', () => {
    it('should POST to /api/token and return TokenResult', async () => {
      adapter.nextResponse = {
        status: 200,
        statusText: 'OK',
        headers: {},
        body: JSON.stringify({
          token: 'test-token-123',
          permissions: ['scanner.read', 'printer.write'],
          expires_at: 1700000000,
        }),
      };

      const result = await transport.requestToken({
        clientId: 'my-app',
        developerKey: 'dev-key-abc',
        requestedPermissions: ['scanner.read', 'printer.write'],
      });

      expect(adapter.lastRequest).not.toBeNull();
      expect(adapter.lastRequest!.url).toBe('http://localhost:8080/api/token');
      expect(adapter.lastRequest!.method).toBe('POST');
      expect(adapter.lastRequest!.headers?.['Content-Type']).toBe('application/json');

      const requestBody = JSON.parse(adapter.lastRequest!.body!);
      expect(requestBody.clientId).toBe('my-app');
      expect(requestBody.developerKey).toBe('dev-key-abc');
      expect(requestBody.requestedPermissions).toEqual(['scanner.read', 'printer.write']);

      expect(result.token).toBe('test-token-123');
      expect(result.permissions).toEqual(['scanner.read', 'printer.write']);
      expect(result.expiresAt).toBe(1700000000);
    });
  });

  describe('execute', () => {
    it('should POST to /api/execute with Bearer token and return result', async () => {
      transport.setToken('my-token');

      adapter.nextResponse = {
        status: 200,
        statusText: 'OK',
        headers: {},
        body: JSON.stringify({ status: 'ok', data: [1, 2, 3] }),
      };

      const result = await transport.execute<{ status: string; data: number[] }>(
        'scanner.scan',
        { timeout: 5000 },
      );

      expect(adapter.lastRequest).not.toBeNull();
      expect(adapter.lastRequest!.url).toBe('http://localhost:8080/api/execute');
      expect(adapter.lastRequest!.method).toBe('POST');
      expect(adapter.lastRequest!.headers?.['Authorization']).toBe('Bearer my-token');

      const requestBody = JSON.parse(adapter.lastRequest!.body!);
      expect(requestBody.method).toBe('scanner.scan');
      expect(requestBody.params).toEqual({ timeout: 5000 });

      expect(result.status).toBe('ok');
      expect(result.data).toEqual([1, 2, 3]);
    });

    it('should throw HalError when no token is set', async () => {
      await expect(transport.execute('scanner.scan')).rejects.toThrow(HalError);
      await expect(transport.execute('scanner.scan')).rejects.toMatchObject({
        code: 'unauthorized',
      });
    });
  });

  describe('error handling', () => {
    it('should throw HalError when response contains error and message', async () => {
      transport.setToken('my-token');

      adapter.nextResponse = {
        status: 403,
        statusText: 'Forbidden',
        headers: {},
        body: JSON.stringify({
          error: 'forbidden',
          message: 'Insufficient permissions for scanner.scan',
        }),
      };

      await expect(transport.execute('scanner.scan')).rejects.toThrow(HalError);
      await expect(transport.execute('scanner.scan')).rejects.toMatchObject({
        code: 'forbidden',
        message: 'Insufficient permissions for scanner.scan',
        httpStatus: 403,
      });
    });

    it('should throw HalError for token request errors', async () => {
      adapter.nextResponse = {
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        body: JSON.stringify({
          error: 'invalid_key',
          message: 'The developer key is invalid',
        }),
      };

      await expect(
        transport.requestToken({ clientId: 'app', developerKey: 'bad-key' }),
      ).rejects.toThrow(HalError);
      await expect(
        transport.requestToken({ clientId: 'app', developerKey: 'bad-key' }),
      ).rejects.toMatchObject({
        code: 'invalid_key',
      });
    });
  });

  describe('token management', () => {
    it('should store and retrieve token', () => {
      expect(transport.getToken()).toBeNull();
      transport.setToken('abc123');
      expect(transport.getToken()).toBe('abc123');
    });

    it('should clear token on dispose', () => {
      transport.setToken('abc123');
      transport.dispose();
      expect(transport.getToken()).toBeNull();
    });
  });
});
