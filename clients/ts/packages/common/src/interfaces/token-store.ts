import type { TokenResult } from '../types/token.js';

export interface ITokenStore {
  getToken(): string | null;
  setToken(result: TokenResult): void;
  clearToken(): void;
  getPermissions(): string[];
  getExpiresAt(): number | null;
  isExpired(): boolean;
}
