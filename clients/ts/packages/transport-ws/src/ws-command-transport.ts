import type {
  ICommandTransport,
  IAuthTransport,
  TokenRequest,
  TokenResult,
  ILogger,
} from '@kduma-autoid/hal-client-common';
import { HalError } from '@kduma-autoid/hal-client-common';
import type { WsConnection } from './ws-connection.js';

export class WsCommandTransport implements ICommandTransport, IAuthTransport {
  private readonly connection: WsConnection;
  private readonly logger?: ILogger;
  private token: string | null = null;

  constructor(connection: WsConnection, logger?: ILogger) {
    this.connection = connection;
    this.logger = logger;
  }

  setToken(token: string): void {
    this.token = token;
    this.logger?.debug('Token set');

    // Fire-and-forget authenticate on the WebSocket connection
    this.connection.authenticate(token).catch((error) => {
      this.logger?.error('WebSocket authenticate failed', error);
    });
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

    if (request.developerKey !== undefined) {
      msg.developerKey = request.developerKey;
    }

    if (request.requestedPermissions !== undefined) {
      msg.requestedPermissions = request.requestedPermissions;
    }

    const response = await this.connection.send(msg);

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }

    if (response.type !== 'response') {
      throw new HalError('parse_error', `Unexpected message type: ${response.type}`);
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

  async execute<T = unknown>(method: string, params?: unknown): Promise<T> {
    if (this.token === null) {
      throw new HalError('unauthorized', 'No token set. Call requestToken() or setToken() first.');
    }

    this.logger?.debug('Executing method', { method });

    const response = await this.connection.send({
      type: 'command',
      method,
      params: JSON.stringify(params),
    });

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }

    if (response.type !== 'response') {
      throw new HalError('parse_error', `Unexpected message type: ${response.type}`);
    }

    this.logger?.debug('Method executed successfully', { method });

    return response.result as T;
  }

  dispose(): void {
    this.token = null;
    this.logger?.debug('WsCommandTransport disposed');
  }
}
