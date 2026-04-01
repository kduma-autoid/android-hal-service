import type {
  ITokenStore,
  IAuthTransport,
  ICommandTransport,
  IEventTransport,
  TokenRequest,
  TokenResult,
} from '@kduma-autoid/hal-client-common';

export interface TokenManagerOptions {
  serviceKey?: string;
  clientId?: string;
  requestedPermissions?: string[];
  onTokenExpired?: () => void;
}

export class TokenManager {
  private tokenStore: ITokenStore;
  private authTransport: IAuthTransport | null;
  private commandTransport: ICommandTransport | null;
  private eventTransport: IEventTransport | null;
  private readonly options: TokenManagerOptions;
  private refreshPromise: Promise<TokenResult> | null = null;

  constructor(
    tokenStore: ITokenStore,
    authTransport?: IAuthTransport,
    commandTransport?: ICommandTransport,
    eventTransport?: IEventTransport,
    options?: TokenManagerOptions,
  ) {
    this.tokenStore = tokenStore;
    this.authTransport = authTransport ?? null;
    this.commandTransport = commandTransport ?? null;
    this.eventTransport = eventTransport ?? null;
    this.options = options ?? {};
  }

  registerAuthTransport(transport: IAuthTransport): void {
    this.authTransport = transport;
  }

  registerCommandTransport(transport: ICommandTransport): void {
    this.commandTransport = transport;
  }

  registerEventTransport(transport: IEventTransport): void {
    this.eventTransport = transport;
  }

  async requestToken(request?: Partial<TokenRequest>): Promise<TokenResult> {
    if (this.authTransport === null) {
      throw new Error('No auth transport configured. Call registerAuthTransport() first.');
    }

    const fullRequest: TokenRequest = {
      clientId: request?.clientId ?? this.options.clientId ?? '',
      serviceKey: request?.serviceKey ?? this.options.serviceKey,
      requestedPermissions: request?.requestedPermissions ?? this.options.requestedPermissions,
    };

    const result = await this.authTransport.requestToken(fullRequest);
    this.tokenStore.setToken(result);
    this.propagateToken(result.token);
    return result;
  }

  authenticateWithExistingToken(token: string): void {
    this.tokenStore.setToken({ token, permissions: [] });
    this.propagateToken(token);
  }

  async ensureValidToken(): Promise<void> {
    if (!this.tokenStore.isExpired()) {
      return;
    }

    // Thundering herd prevention: reuse in-flight refresh promise
    if (this.refreshPromise !== null) {
      await this.refreshPromise;
      return;
    }

    if (this.options.serviceKey && this.authTransport !== null) {
      this.refreshPromise = this.requestToken().finally(() => {
        this.refreshPromise = null;
      });
      await this.refreshPromise;
      return;
    }

    if (this.options.onTokenExpired) {
      this.options.onTokenExpired();
    }

    throw new Error('Token is expired and cannot be auto-refreshed. No service key configured.');
  }

  getToken(): string | null {
    return this.tokenStore.getToken();
  }

  getPermissions(): string[] {
    return this.tokenStore.getPermissions();
  }

  isAuthenticated(): boolean {
    const token = this.tokenStore.getToken();
    if (token === null) {
      return false;
    }
    return !this.tokenStore.isExpired();
  }

  clearToken(): void {
    this.tokenStore.clearToken();
    this.propagateToken('');
  }

  private propagateToken(token: string): void {
    if (token && this.commandTransport) {
      this.commandTransport.setToken(token);
    }
    if (token && this.eventTransport) {
      this.eventTransport.setToken(token);
    }
  }
}
