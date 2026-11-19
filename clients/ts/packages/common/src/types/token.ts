export interface TokenRequest {
  clientId: string;
  serviceKey?: string;
  requestedPermissions?: string[];
}

export interface TokenResult {
  token: string;
  permissions: string[];
  expiresAt?: number;
}

/**
 * Where the client's authorization stands:
 * - `unauthenticated` — no token yet (or cleared);
 * - `authenticated` — a token the service accepts as far as the client knows;
 * - `refreshing` — a new token is being obtained automatically (service key / `autoRequestToken`);
 * - `expired` — the service rejected the token (expired or revoked) and it cannot be renewed
 *   automatically: the app should call `requestToken()` (which may show the permission dialog);
 * - `denied` — the last token request was refused (`user_denied`, `invalid_key`, `key_expired`,
 *   `restriction_mismatch`).
 */
export type AuthState = 'unauthenticated' | 'authenticated' | 'refreshing' | 'expired' | 'denied';

export type AuthStateHandler = (state: AuthState) => void;
