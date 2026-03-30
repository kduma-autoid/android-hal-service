import type { ITokenStore } from './interfaces/token-store.js';
import type { TokenResult } from './types/token.js';

const EXPIRY_MARGIN_MS = 30_000;

export class InMemoryTokenStore implements ITokenStore {
  private token: string | null = null;
  private permissions: string[] = [];
  private expiresAt: number | null = null;

  getToken(): string | null {
    return this.token;
  }

  setToken(result: TokenResult): void {
    this.token = result.token;
    this.permissions = result.permissions;
    this.expiresAt = result.expiresAt ?? null;
  }

  clearToken(): void {
    this.token = null;
    this.permissions = [];
    this.expiresAt = null;
  }

  getPermissions(): string[] {
    return this.permissions;
  }

  getExpiresAt(): number | null {
    return this.expiresAt;
  }

  isExpired(): boolean {
    if (this.token === null) {
      return true;
    }
    if (this.expiresAt === null) {
      return false;
    }
    return Date.now() >= this.expiresAt - EXPIRY_MARGIN_MS;
  }
}
