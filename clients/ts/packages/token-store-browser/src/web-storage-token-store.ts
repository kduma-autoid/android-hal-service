import type { ITokenStore, TokenResult } from '@kduma-autoid/hal-client-common';

const EXPIRY_MARGIN_MS = 30_000;

interface StoredTokenData {
  token: string;
  permissions: string[];
  expiresAt: number | null;
}

export class WebStorageTokenStore implements ITokenStore {
  constructor(
    private readonly storage: Storage,
    private readonly key: string = 'hal_token',
  ) {}

  getToken(): string | null {
    return this.read()?.token ?? null;
  }

  setToken(result: TokenResult): void {
    const data: StoredTokenData = {
      token: result.token,
      permissions: result.permissions,
      expiresAt: result.expiresAt ?? null,
    };
    this.storage.setItem(this.key, JSON.stringify(data));
  }

  clearToken(): void {
    this.storage.removeItem(this.key);
  }

  getPermissions(): string[] {
    return this.read()?.permissions ?? [];
  }

  getExpiresAt(): number | null {
    return this.read()?.expiresAt ?? null;
  }

  isExpired(): boolean {
    const data = this.read();
    if (data === null) return true;
    if (data.expiresAt === null) return false;
    return Date.now() >= data.expiresAt - EXPIRY_MARGIN_MS;
  }

  private read(): StoredTokenData | null {
    const raw = this.storage.getItem(this.key);
    if (raw === null) return null;
    try {
      return JSON.parse(raw) as StoredTokenData;
    } catch {
      return null;
    }
  }
}
