import type {
  ITokenStore,
  IAuthTransport,
  ICommandTransport,
  IEventTransport,
  TokenRequest,
  TokenResult,
  AuthState,
  AuthStateHandler,
} from '@kduma-autoid/hal-client-common';
import { HalError, TOKEN_DENIAL_CODES } from '@kduma-autoid/hal-client-common';

/**
 * A service key, or a function producing one per token request — e.g. signing a fresh short-lived
 * device-key JWT, so automatic token renewal never presents an expired key.
 */
export type ServiceKeyProvider = string | (() => string | Promise<string>);

export interface TokenManagerOptions {
  serviceKey?: ServiceKeyProvider;
  clientId?: string;
  requestedPermissions?: string[];
  /** Called when the token expires or is rejected and cannot be renewed automatically. */
  onTokenExpired?: () => void;
  /**
   * Renew a rejected token automatically even without a service key, by requesting a new one — which
   * shows the permission dialog on the device unless a still-valid grant exists. Default `false`: the
   * auth state turns `expired` and the app decides when to call `requestToken()`.
   */
  autoRequestToken?: boolean;
}

/** Automatic renewals closer together than this, for the same rejected token, give up instead of looping. */
const MIN_AUTO_REFRESH_INTERVAL_MS = 5000;
/** setTimeout's maximum delay (~24.8 days). */
const MAX_TIMER_MS = 2 ** 31 - 1;

export class TokenManager {
  private tokenStore: ITokenStore;
  private authTransport: IAuthTransport | null;
  private commandTransport: ICommandTransport | null;
  private eventTransport: IEventTransport | null;
  private readonly options: TokenManagerOptions;
  private refreshPromise: Promise<TokenResult> | null = null;

  private _authState: AuthState;
  private authStateHandlers: AuthStateHandler[] = [];
  private expiryTimer: ReturnType<typeof setTimeout> | null = null;
  private lastAutoRefresh: { at: number; rejectedToken: string | null } | null = null;
  /**
   * The service hands back the same token while it is still valid, so a renewal inside the store's
   * early-expiry margin can return the token we already have. Until its real expiry it is good — don't
   * keep asking for it before every command.
   */
  private confirmedUntil = 0;

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
    this._authState =
      tokenStore.getToken() !== null && !tokenStore.isExpired() ? 'authenticated' : 'unauthenticated';
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

  // --- Auth state ---

  get authState(): AuthState {
    return this._authState;
  }

  onAuthStateChange(handler: AuthStateHandler): () => void {
    this.authStateHandlers.push(handler);
    return () => {
      this.authStateHandlers = this.authStateHandlers.filter((h) => h !== handler);
    };
  }

  /** Whether a rejected or expired token can be replaced without the app's involvement. */
  get canRenewAutomatically(): boolean {
    return (
      this.authTransport !== null &&
      (this.options.serviceKey !== undefined || this.options.autoRequestToken === true)
    );
  }

  // --- Tokens ---

  async requestToken(request?: Partial<TokenRequest>): Promise<TokenResult> {
    if (this.authTransport === null) {
      throw new Error('No auth transport configured. Call registerAuthTransport() first.');
    }

    const fullRequest: TokenRequest = {
      clientId: request?.clientId ?? this.options.clientId ?? '',
      // Awaited only for a key function: a plain key keeps the request synchronous up to the
      // transport call, which concurrent callers rely on to share one in-flight renewal.
      serviceKey:
        request?.serviceKey ??
        (typeof this.options.serviceKey === 'function' ? await this.options.serviceKey() : this.options.serviceKey),
      requestedPermissions: request?.requestedPermissions ?? this.options.requestedPermissions,
    };

    const previous = this.tokenStore.getToken();
    let result: TokenResult;
    try {
      result = await this.authTransport.requestToken(fullRequest);
    } catch (error) {
      if (error instanceof HalError && TOKEN_DENIAL_CODES.includes(error.code)) {
        this.setAuthState('denied');
      }
      throw error;
    }

    this.confirmedUntil = result.token === previous && result.expiresAt !== undefined ? result.expiresAt : 0;
    this.tokenStore.setToken(result);
    this.propagateToken(result.token);
    this.setAuthState('authenticated');
    this.scheduleExpiryRenewal(result);
    return result;
  }

  authenticateWithExistingToken(token: string): void {
    this.tokenStore.setToken({ token, permissions: [] });
    this.confirmedUntil = 0;
    this.clearExpiryTimer();
    this.propagateToken(token);
    this.setAuthState('authenticated');
  }

  /** Before a request: renew a locally expired token if possible, otherwise fail with `unauthorized`. */
  async ensureValidToken(): Promise<void> {
    // Thundering herd prevention: reuse in-flight refresh promise
    if (this.refreshPromise !== null) {
      await this.refreshPromise;
      return;
    }

    if (!this.tokenStore.isExpired() || Date.now() < this.confirmedUntil) {
      return;
    }

    if (this.canRenewAutomatically) {
      await this.renew();
      return;
    }

    this.markExpired();
    throw new HalError(
      'unauthorized',
      'Token is expired and cannot be auto-refreshed. No service key configured — call requestToken().',
    );
  }

  /**
   * The service rejected `rejectedToken` (`unauthorized`). Resolves `true` when a usable token is in
   * place and the request may be retried once: renewed automatically, or already replaced meanwhile
   * by a concurrent renewal. Resolves `false` — auth state `expired`, `onTokenExpired` called — when it
   * cannot be renewed without the app.
   */
  async handleUnauthorized(rejectedToken: string | null): Promise<boolean> {
    const current = this.tokenStore.getToken();
    if (current !== null && current !== rejectedToken && this._authState === 'authenticated') {
      return true;
    }

    if (this.refreshPromise !== null) {
      return this.refreshPromise.then(
        () => true,
        () => false,
      );
    }

    if (!this.canRenewAutomatically) {
      this.markExpired();
      return false;
    }

    // A renewal that the service rejects right away again (e.g. a binding that no longer matches)
    // would loop; stop and let the app decide.
    const last = this.lastAutoRefresh;
    if (last !== null && last.rejectedToken === rejectedToken && Date.now() - last.at < MIN_AUTO_REFRESH_INTERVAL_MS) {
      this.markExpired();
      return false;
    }
    this.lastAutoRefresh = { at: Date.now(), rejectedToken };

    try {
      await this.renew();
      return true;
    } catch {
      return false;
    }
  }

  getToken(): string | null {
    return this.tokenStore.getToken();
  }

  getPermissions(): string[] {
    return this.tokenStore.getPermissions();
  }

  isAuthenticated(): boolean {
    const token = this.tokenStore.getToken();
    if (token === null || this._authState === 'expired') {
      return false;
    }
    return !this.tokenStore.isExpired() || Date.now() < this.confirmedUntil;
  }

  clearToken(): void {
    this.clearExpiryTimer();
    this.confirmedUntil = 0;
    this.tokenStore.clearToken();
    this.propagateToken('');
    this.setAuthState('unauthenticated');
  }

  /** Stops timers; call when the client is disposed. */
  dispose(): void {
    this.clearExpiryTimer();
    this.authStateHandlers = [];
  }

  // --- Internals ---

  /** One automatic renewal at a time; concurrent callers share it. */
  private renew(): Promise<TokenResult> {
    if (this.refreshPromise !== null) return this.refreshPromise;

    this.setAuthState('refreshing');
    const refresh = this.requestToken()
      .catch((error: unknown) => {
        // A refusal already set `denied`; anything else (network, timeout) leaves us without a token.
        if (this._authState === 'refreshing') this.markExpired();
        throw error;
      })
      .finally(() => {
        if (this.refreshPromise === refresh) this.refreshPromise = null;
      });
    this.refreshPromise = refresh;
    return refresh;
  }

  /**
   * Renew a renewable token right after it expires, so a long-lived connection (e.g. one only
   * receiving events) gets a fresh token without waiting for a command to fail. Renewing *before*
   * expiry is pointless: the service returns the still-valid token it already issued.
   */
  private scheduleExpiryRenewal(result: TokenResult): void {
    this.clearExpiryTimer();
    if (!this.canRenewAutomatically || this.options.serviceKey === undefined || result.expiresAt === undefined) return;

    const delay = Math.min(Math.max(result.expiresAt - Date.now() + 1000, 1000), MAX_TIMER_MS);
    this.expiryTimer = setTimeout(() => {
      this.expiryTimer = null;
      this.renew().catch(() => undefined);
    }, delay);
  }

  private clearExpiryTimer(): void {
    if (this.expiryTimer !== null) {
      clearTimeout(this.expiryTimer);
      this.expiryTimer = null;
    }
  }

  private markExpired(): void {
    this.clearExpiryTimer();
    if (this._authState === 'expired') return;
    this.setAuthState('expired');
    this.options.onTokenExpired?.();
  }

  private setAuthState(state: AuthState): void {
    if (this._authState === state) return;
    this._authState = state;
    for (const handler of this.authStateHandlers) {
      try {
        handler(state);
      } catch {
        // A faulty listener must not break token handling.
      }
    }
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
