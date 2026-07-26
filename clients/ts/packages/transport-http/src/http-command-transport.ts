import type {
  ICommandTransport,
  IAuthTransport,
  ILogger,
  TokenRequest,
  TokenResult,
  ExecuteOptions,
} from '@kduma-autoid/hal-client-common';
import {
  HalError,
  HalTransportError,
  HalTimeoutError,
  isHalErrorResponse,
} from '@kduma-autoid/hal-client-common';
import type { IHttpAdapter } from './interfaces/http-adapter.js';
import { DefaultHttpAdapter } from './default-http-adapter.js';

export interface HttpCommandTransportOptions {
  baseUrl: string;
  httpAdapter?: IHttpAdapter;
  timeout?: number;
  logger?: ILogger;
}

const DEFAULT_TIMEOUT = 30000;

export class HttpCommandTransport implements ICommandTransport, IAuthTransport {
  private readonly baseUrl: string;
  private readonly httpAdapter: IHttpAdapter;
  private readonly timeout: number;
  private readonly logger?: ILogger;
  private token: string | null = null;

  constructor(options: HttpCommandTransportOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.httpAdapter = options.httpAdapter ?? new DefaultHttpAdapter();
    this.timeout = options.timeout ?? DEFAULT_TIMEOUT;
    this.logger = options.logger;
  }

  setToken(token: string): void {
    this.token = token;
    this.logger?.debug('Token set');
  }

  getToken(): string | null {
    return this.token;
  }

  async requestToken(request: TokenRequest): Promise<TokenResult> {
    this.logger?.debug('Requesting token', { clientId: request.clientId });

    const body: Record<string, unknown> = {
      clientId: request.clientId,
    };
    if (request.serviceKey !== undefined) {
      body.serviceKey = request.serviceKey;
    }
    if (request.requestedPermissions !== undefined) {
      body.requestedPermissions = request.requestedPermissions;
    }

    const response = await this.doRequest('POST', '/api/token', body);

    const result = this.parseJson(response.body);

    if (isHalErrorResponse(result)) {
      throw new HalError(result.error, result.message, response.status);
    }

    const tokenResult: TokenResult = {
      token: (result as Record<string, unknown>).token as string,
      permissions: (result as Record<string, unknown>).permissions as string[],
    };

    const expiresAt = (result as Record<string, unknown>).expires_at;
    if (expiresAt !== undefined && expiresAt !== null) {
      tokenResult.expiresAt = expiresAt as number;
    }

    this.logger?.info('Token acquired', { permissions: tokenResult.permissions });

    return tokenResult;
  }

  async execute<T = unknown>(method: string, params?: unknown, options?: ExecuteOptions): Promise<T> {
    if (this.token === null) {
      throw new HalError('unauthorized', 'No token set. Call requestToken() or setToken() first.');
    }

    this.logger?.debug('Executing method', { method });

    const response = await this.doRequest(
      'POST',
      '/api/execute',
      { method, params },
      { Authorization: `Bearer ${this.token}` },
    );

    const result = this.parseJson(response.body);

    if (isHalErrorResponse(result)) {
      throw new HalError(result.error, result.message, response.status);
    }

    this.logger?.debug('Method executed successfully', { method });

    // Handling provider (interface methods) arrives in the X-Hal-Provider response header.
    const provider = response.headers['x-hal-provider'];
    options?.onMeta?.(provider ? { provider } : {});

    return result as T;
  }

  dispose(): void {
    this.token = null;
    this.logger?.debug('HttpCommandTransport disposed');
  }

  private async doRequest(
    method: 'GET' | 'POST',
    path: string,
    body?: unknown,
    extraHeaders?: Record<string, string>,
  ) {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...extraHeaders,
    };

    try {
      return await this.httpAdapter.request({
        url,
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        timeout: this.timeout,
      });
    } catch (error: unknown) {
      if (error instanceof HalError || error instanceof HalTransportError) {
        throw error;
      }

      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new HalTimeoutError(`Request to ${path} timed out after ${this.timeout}ms`);
      }

      if (error instanceof TypeError) {
        throw new HalTransportError(`Network error: ${error.message}`, error);
      }

      throw new HalTransportError(
        `Unexpected error during request to ${path}: ${String(error)}`,
        error,
      );
    }
  }

  private parseJson(text: string): unknown {
    try {
      return JSON.parse(text);
    } catch {
      throw new HalTransportError(`Failed to parse response as JSON: ${text.substring(0, 200)}`);
    }
  }
}
