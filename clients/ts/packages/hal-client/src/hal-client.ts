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
} from '@kduma-autoid/hal-client-common';
import { InMemoryTokenStore, EventSubscriberAdapter } from '@kduma-autoid/hal-client-common';
import type { HalClientOptions } from './hal-client-options.js';
import { TokenManager } from './token-manager.js';

export class HalClient implements IHalClient {
  private readonly tokenManager: TokenManager;
  private connection: IConnectable | null = null;
  private commandTransport: ICommandTransport | null = null;
  private eventTransport: IEventTransport | null = null;
  private eventSubscriber: EventSubscriberAdapter | null = null;

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

  // --- Commands ---

  async execute<T = unknown>(method: string, params?: unknown, options?: ExecuteOptions): Promise<T> {
    if (this.commandTransport === null) {
      throw new Error('No command transport configured. Call useCommandTransport() first.');
    }

    await this.tokenManager.ensureValidToken();
    return this.commandTransport.execute<T>(method, params, options);
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
    return this.eventSubscriber.on<T>(event, handler);
  }

  // --- Lifecycle ---

  dispose(): void {
    this.tokenManager.clearToken();

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
