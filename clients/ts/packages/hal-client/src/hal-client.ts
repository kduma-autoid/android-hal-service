import type {
  IConnectable,
  IAuthTransport,
  ICommandTransport,
  IEventTransport,
  IHalClient,
  ConnectionState,
  ConnectionStateHandler,
  TokenRequest,
  TokenResult,
  HealthResponse,
  StatusResponse,
  DescribeOptions,
  DescribeResponse,
  EventMeta,
  ExecuteOptions,
  AuthState,
  AuthStateHandler,
} from '@kduma-autoid/hal-client-common';
import { InMemoryTokenStore, EventSubscriberAdapter, isAuthError } from '@kduma-autoid/hal-client-common';
import type { HalClientOptions } from './hal-client-options.js';
import { TokenManager } from './token-manager.js';

export class HalClient implements IHalClient {
  private readonly tokenManager: TokenManager;
  private connection: IConnectable | null = null;
  private commandTransport: ICommandTransport | null = null;
  private eventTransport: IEventTransport | null = null;
  private eventSubscriber: EventSubscriberAdapter | null = null;
  private removeTokenRejected: (() => void) | null = null;

  constructor(options: HalClientOptions) {
    const tokenStore = options.tokenStore ?? new InMemoryTokenStore();
    this.tokenManager = new TokenManager(
      tokenStore,
      undefined,
      undefined,
      undefined,
      {
        serviceKey: options.serviceKey,
        clientId: options.clientId,
        requestedPermissions: options.requestedPermissions,
        onTokenExpired: options.onTokenExpired,
        autoRequestToken: options.autoRequestToken,
      },
    );
  }

  // --- Wiring ---

  useConnection(connection: IConnectable): this {
    this.connection = connection;
    return this;
  }

  useAuthTransport(transport: IAuthTransport): this {
    this.tokenManager.registerAuthTransport(transport);
    return this;
  }

  useCommandTransport(transport: ICommandTransport): this {
    this.commandTransport = transport;
    this.tokenManager.registerCommandTransport(transport);
    // A token rejected outside a command (WebSocket re-authenticating after a reconnect) is handled
    // like a rejected command: renewed if possible, otherwise the auth state turns `expired`.
    this.removeTokenRejected?.();
    this.removeTokenRejected =
      transport.onTokenRejected?.((token) => {
        void this.tokenManager.handleUnauthorized(token);
      }) ?? null;
    return this;
  }

  useEventTransport(transport: IEventTransport): this {
    this.eventTransport = transport;
    this.eventSubscriber = new EventSubscriberAdapter(transport);
    this.tokenManager.registerEventTransport(transport);
    return this;
  }

  // --- Auth ---

  async requestToken(request?: Partial<TokenRequest>): Promise<TokenResult> {
    return this.tokenManager.requestToken(request);
  }

  async authenticate(token: string): Promise<void> {
    this.tokenManager.authenticateWithExistingToken(token);
  }

  get isAuthenticated(): boolean {
    return this.tokenManager.isAuthenticated();
  }

  get token(): string | null {
    return this.tokenManager.getToken();
  }

  get permissions(): string[] {
    return this.tokenManager.getPermissions();
  }

  /** Where authorization stands — see {@link AuthState}. */
  get authState(): AuthState {
    return this.tokenManager.authState;
  }

  /** Subscribes to auth state changes, e.g. to prompt for `requestToken()` once it turns `expired`. */
  onAuthStateChange(handler: AuthStateHandler): () => void {
    return this.tokenManager.onAuthStateChange(handler);
  }

  // --- Commands ---

  async execute<T = unknown>(method: string, params?: unknown, options?: ExecuteOptions): Promise<T> {
    if (this.commandTransport === null) {
      throw new Error('No command transport configured. Call useCommandTransport() first.');
    }

    const transport = this.commandTransport;
    await this.tokenManager.ensureValidToken();
    return this.withAuthRetry(() => transport.execute<T>(method, params, options));
  }

  /**
   * Runs `call`; if the service rejects the token (`unauthorized`: expired, revoked), renews it when
   * possible and retries once. Otherwise the error propagates and the auth state turns `expired`.
   */
  private async withAuthRetry<T>(call: () => Promise<T>): Promise<T> {
    const used = this.tokenManager.getToken();
    try {
      return await call();
    } catch (error) {
      if (!isAuthError(error) || !(await this.tokenManager.handleUnauthorized(used))) throw error;
      return call();
    }
  }

  // --- System convenience ---

  async getHealth(): Promise<HealthResponse> {
    return this.execute<HealthResponse>('system.ping');
  }

  async getStatus(): Promise<StatusResponse> {
    return this.execute<StatusResponse>('system.status');
  }

  async getDescribe(options?: DescribeOptions): Promise<DescribeResponse> {
    return this.execute<DescribeResponse>('system.describe', options);
  }

  // --- Connection ---

  async connect(): Promise<void> {
    if (this.connection === null) {
      throw new Error('No connection configured. Call useConnection() first.');
    }
    return this.connection.connect();
  }

  disconnect(permanent?: boolean): void {
    if (this.connection === null) {
      throw new Error('No connection configured. Call useConnection() first.');
    }
    this.connection.disconnect(permanent);
  }

  get connectionState(): ConnectionState {
    if (this.connection === null) {
      return 'disconnected';
    }
    return this.connection.connectionState;
  }

  onConnectionStateChange(handler: ConnectionStateHandler): () => void {
    if (this.connection === null) {
      throw new Error('No connection configured. Call useConnection() first.');
    }
    return this.connection.onConnectionStateChange(handler);
  }

  // --- Events (IEventSubscriber) ---

  async on<T = unknown>(
    event: string,
    handler: (eventName: string, data: T, meta?: EventMeta) => void,
  ): Promise<() => Promise<void>> {
    if (this.eventSubscriber === null) {
      throw new Error('No event transport configured. Call useEventTransport() first.');
    }
    const subscriber = this.eventSubscriber;
    return this.withAuthRetry(() => subscriber.on<T>(event, handler));
  }

  // --- Lifecycle ---

  dispose(): void {
    this.removeTokenRejected?.();
    this.removeTokenRejected = null;
    this.tokenManager.clearToken();
    this.tokenManager.dispose();

    if (this.eventTransport) {
      this.eventSubscriber = null;
      this.eventTransport.dispose();
      this.eventTransport = null;
    }

    if (this.commandTransport) {
      this.commandTransport.dispose();
      this.commandTransport = null;
    }

    if (this.connection) {
      this.connection.disconnect(true);
      this.connection = null;
    }
  }
}
