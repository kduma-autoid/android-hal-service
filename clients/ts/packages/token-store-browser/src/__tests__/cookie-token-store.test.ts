// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { CookieTokenStore } from '../cookie-token-store.js';

describe('CookieTokenStore', () => {
  let cookieJar: string;

  beforeEach(() => {
    cookieJar = '';
    Object.defineProperty(document, 'cookie', {
      get: () => cookieJar,
      set: (value: string) => {
        const parts = value.split(';');
        const [nameValue] = parts;
        const [name] = nameValue.split('=');
        const maxAgePart = parts.find(p => p.trim().startsWith('Max-Age='));
        const maxAge = maxAgePart ? parseInt(maxAgePart.split('=')[1]) : null;

        if (maxAge !== null && maxAge <= 0) {
          // Remove cookie
          const existing = cookieJar.split('; ').filter(c => !c.startsWith(`${name.trim()}=`));
          cookieJar = existing.join('; ');
        } else {
          // Add/update cookie
          const existing = cookieJar.split('; ').filter(c => c && !c.startsWith(`${name.trim()}=`));
          existing.push(nameValue.trim());
          cookieJar = existing.join('; ');
        }
      },
      configurable: true,
    });
  });

  it('returns null when no cookie set', () => {
    const store = new CookieTokenStore();
    expect(store.getToken()).toBeNull();
    expect(store.getPermissions()).toEqual([]);
    expect(store.isExpired()).toBe(true);
  });

  it('stores and retrieves token', () => {
    const store = new CookieTokenStore();
    store.setToken({ token: 'abc', permissions: ['read'] });
    expect(store.getToken()).toBe('abc');
    expect(store.getPermissions()).toEqual(['read']);
  });

  it('uses custom cookie name', () => {
    const store = new CookieTokenStore({ name: 'my_tok' });
    store.setToken({ token: 'xyz', permissions: [] });
    expect(cookieJar).toContain('my_tok=');
    expect(store.getToken()).toBe('xyz');
  });

  it('clearToken removes cookie', () => {
    const store = new CookieTokenStore();
    store.setToken({ token: 'tok', permissions: [] });
    expect(store.getToken()).toBe('tok');
    store.clearToken();
    expect(store.getToken()).toBeNull();
  });

  it('isExpired with valid token', () => {
    const store = new CookieTokenStore();
    store.setToken({ token: 'tok', permissions: [], expiresAt: Date.now() + 120_000 });
    expect(store.isExpired()).toBe(false);
  });

  it('isExpired within margin', () => {
    const store = new CookieTokenStore();
    store.setToken({ token: 'tok', permissions: [], expiresAt: Date.now() + 20_000 });
    expect(store.isExpired()).toBe(true);
  });

  it('sets cookie attributes', () => {
    const setCalls: string[] = [];
    Object.defineProperty(document, 'cookie', {
      get: () => cookieJar,
      set: (value: string) => {
        setCalls.push(value);
        const parts = value.split(';');
        const [nameValue] = parts;
        const existing = cookieJar.split('; ').filter(c => c && !c.startsWith(nameValue.split('=')[0].trim() + '='));
        existing.push(nameValue.trim());
        cookieJar = existing.join('; ');
      },
      configurable: true,
    });

    const store = new CookieTokenStore({
      path: '/app',
      domain: '.example.com',
      secure: true,
      sameSite: 'Strict',
    });
    store.setToken({ token: 'tok', permissions: [], expiresAt: Date.now() + 60_000 });

    const lastSet = setCalls[setCalls.length - 1];
    expect(lastSet).toContain('Path=/app');
    expect(lastSet).toContain('Domain=.example.com');
    expect(lastSet).toContain('Secure');
    expect(lastSet).toContain('SameSite=Strict');
    expect(lastSet).toContain('Max-Age=');
  });
});
