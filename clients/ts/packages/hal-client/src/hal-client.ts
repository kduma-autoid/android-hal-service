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
  HealthResponse,
  StatusResponse,
  DescribeOptions,
  DescribeResponse,
} from '@kduma-autoid/hal-client-common';
import { InMemoryTokenStore } from '@kduma-autoid/hal-client-common';
import type { HalClientOptions } from './hal-client-options.js';
import { TokenManager } from './token-manager.js';

export class HalClient {
  private readonly tokenManager: TokenManager;
  private connection: IConnectable | null = null;
  private commandTransport: ICommandTransport | null = null;
  private eventTransport: IEventTransport | null = null;

  constructor(options: HalClientOptions) {
    const tokenStore = options.tokenStore ?? new InMemoryTokenStore();
    this.tokenManager = new TokenManager(
      tokenStore,
      undefined,
      undefined,
      undefined,
      {
        developerKey: options.developerKey,
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

  async execute<T = unknown>(method: string, params?: unknown): Promise<T> {
    if (this.commandTransport === null) {
      throw new Error('No command transport configured. Call useCommandTransport() first.');
    }

    await this.tokenManager.ensureValidToken();
    return this.commandTransport.execute<T>(method, params);
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

  // --- Events ---

  async subscribe(events: string[]): Promise<void> {
    if (this.eventTransport === null) {
      throw new Error('No event transport configured. Call useEventTransport() first.');
    }
    return this.eventTransport.subscribe(events);
  }

  async unsubscribe(events: string[]): Promise<void> {
    if (this.eventTransport === null) {
      throw new Error('No event transport configured. Call useEventTransport() first.');
    }
    return this.eventTransport.unsubscribe(events);
  }

  on<T = unknown>(pattern: string, handler: EventHandler<T>): () => void {
    if (this.eventTransport === null) {
      throw new Error('No event transport configured. Call useEventTransport() first.');
    }
    return this.eventTransport.on<T>(pattern, handler);
  }

  off(pattern: string): void {
    if (this.eventTransport === null) {
      throw new Error('No event transport configured. Call useEventTransport() first.');
    }
    this.eventTransport.off(pattern);
  }

  // --- Lifecycle ---

  dispose(): void {
    this.tokenManager.clearToken();

    if (this.eventTransport) {
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
