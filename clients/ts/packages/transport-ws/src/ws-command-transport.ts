import type {
  ICommandTransport,
  IAuthTransport,
  TokenRequest,
  TokenResult,
  ILogger,
  ExecuteOptions,
} from '@kduma-autoid/hal-client-common';
import { HalError, isHalErrorResponse } from '@kduma-autoid/hal-client-common';
import type { WsConnection } from './ws-connection.js';

export interface WsCommandTransportOptions {
  /**
   * Timeout for token requests, in ms. Without a service key the service shows a permission dialog
   * and waits up to 60 s for an answer, so this is longer than the normal request timeout. Default 75000.
   */
  tokenRequestTimeout?: number;
}

/** Longer than the service's 60 s permission dialog, so the client hears the answer (or `timeout`). */
const DEFAULT_TOKEN_REQUEST_TIMEOUT = 75_000;

export class WsCommandTransport implements ICommandTransport, IAuthTransport {
  private readonly connection: WsConnection;
  private readonly logger?: ILogger;
  private token: string | null = null;
  private rejectedHandlers: Array<(token: string) => void> = [];
  private readonly removeSessionHook: () => void;
  private readonly tokenRequestTimeout: number;

  constructor(connection: WsConnection, logger?: ILogger, options?: WsCommandTransportOptions) {
    this.connection = connection;
    this.logger = logger;
    this.tokenRequestTimeout = options?.tokenRequestTimeout ?? DEFAULT_TOKEN_REQUEST_TIMEOUT;

    // The server keeps the token per socket: every new socket (and every new token) is authenticated
    // first, before anything else may use the connection. Order 0 — event re-subscription runs after.
    this.removeSessionHook = connection.addSessionHook(async (send) => {
      const token = this.token;
      if (token === null) return;
      const response = await send({ type: 'authenticate', token });
      if (response.type === 'error') {
        this.logger?.warn('WebSocket authenticate rejected', { code: response.error.code });
        if (response.error.code === 'unauthorized') this.notifyRejected(token);
        throw new HalError(response.error.code, response.error.message);
      }
    }, 0);
  }

  setToken(token: string): void {
    this.token = token;
    this.logger?.debug('Token set');
    // Authenticates (and re-subscribes) on the live socket; requests sent meanwhile wait for it.
    void this.connection.restoreSession();
  }

  onTokenRejected(handler: (token: string) => void): () => void {
    this.rejectedHandlers.push(handler);
    return () => {
      this.rejectedHandlers = this.rejectedHandlers.filter((h) => h !== handler);
    };
  }

  private notifyRejected(token: string): void {
    for (const handler of this.rejectedHandlers) {
      try {
        handler(token);
      } catch (e) {
        this.logger?.error('Token-rejected handler threw', e);
      }
    }
  }

  getToken(): string | null {
    return this.token;
  }

  async requestToken(request: TokenRequest): Promise<TokenResult> {
    this.logger?.debug('Requesting token', { clientId: request.clientId });

    const msg: Record<string, unknown> = {
      type: 'requestToken',
      clientId: request.clientId,
    };

    if (request.serviceKey !== undefined) {
      msg.serviceKey = request.serviceKey;
    }

    if (request.requestedPermissions !== undefined) {
      msg.requestedPermissions = request.requestedPermissions;
    }

    const response = await this.connection.send(msg, { timeout: this.tokenRequestTimeout });

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }

    if (response.type !== 'response') {
      throw new HalError('parse_error', `Unexpected message type: ${response.type}`);
    }

    if (isHalErrorResponse(response.result)) {
      throw new HalError(response.result.error, response.result.message);
    }

    const result = response.result as Record<string, unknown>;

    const tokenResult: TokenResult = {
      token: result.token as string,
      permissions: result.permissions as string[],
    };

    const expiresAt = result.expires_at;
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

    const response = await this.connection.send({
      type: 'command',
      method,
      params: params ?? {},
    });

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }

    if (response.type !== 'response') {
      throw new HalError('parse_error', `Unexpected message type: ${response.type}`);
    }

    if (isHalErrorResponse(response.result)) {
      throw new HalError(response.result.error, response.result.message);
    }

    this.logger?.debug('Method executed successfully', { method });

    // Surface the handling provider (frame header) without changing the return value.
    options?.onMeta?.(response.provider !== undefined ? { provider: response.provider } : {});

    return response.result as T;
  }

  dispose(): void {
    this.token = null;
    this.rejectedHandlers = [];
    this.removeSessionHook();
    this.logger?.debug('WsCommandTransport disposed');
  }
}
