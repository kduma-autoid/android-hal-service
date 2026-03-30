import type { ITokenStore, TokenResult } from '@kduma-autoid/hal-client-common';

const EXPIRY_MARGIN_MS = 30_000;

export interface CookieTokenStoreOptions {
  name?: string;
  path?: string;
  domain?: string;
  secure?: boolean;
  sameSite?: 'Strict' | 'Lax' | 'None';
}

interface StoredTokenData {
  token: string;
  permissions: string[];
  expiresAt: number | null;
}

export class CookieTokenStore implements ITokenStore {
  private readonly name: string;
  private readonly path: string;
  private readonly domain?: string;
  private readonly secure: boolean;
  private readonly sameSite: string;

  constructor(options: CookieTokenStoreOptions = {}) {
    this.name = options.name ?? 'hal_token';
    this.path = options.path ?? '/';
    this.domain = options.domain;
    this.secure = options.secure ?? false;
    this.sameSite = options.sameSite ?? 'Lax';
  }

  getToken(): string | null {
    return this.read()?.token ?? null;
  }

  setToken(result: TokenResult): void {
    const data: StoredTokenData = {
      token: result.token,
      permissions: result.permissions,
      expiresAt: result.expiresAt ?? null,
    };

    const value = encodeURIComponent(JSON.stringify(data));
    let cookie = `${this.name}=${value}; Path=${this.path}; SameSite=${this.sameSite}`;

    if (result.expiresAt != null) {
      const maxAge = Math.max(0, Math.floor((result.expiresAt - Date.now()) / 1000));
      cookie += `; Max-Age=${maxAge}`;
    }

    if (this.domain) {
      cookie += `; Domain=${this.domain}`;
    }

    if (this.secure) {
      cookie += '; Secure';
    }

    document.cookie = cookie;
  }

  clearToken(): void {
    let cookie = `${this.name}=; Path=${this.path}; Max-Age=0`;
    if (this.domain) {
      cookie += `; Domain=${this.domain}`;
    }
    document.cookie = cookie;
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
    const prefix = `${this.name}=`;
    const cookies = document.cookie.split('; ');
    for (const entry of cookies) {
      if (entry.startsWith(prefix)) {
        try {
          const value = decodeURIComponent(entry.slice(prefix.length));
          return JSON.parse(value) as StoredTokenData;
        } catch {
          return null;
        }
      }
    }
    return null;
  }
}
