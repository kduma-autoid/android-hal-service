import { describe, it, expect, beforeEach } from 'vitest';
import { WebStorageTokenStore } from '../web-storage-token-store.js';

function createMockStorage(): Storage {
  const store = new Map<string, string>();
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => { store.set(key, value); },
    removeItem: (key: string) => { store.delete(key); },
    clear: () => { store.clear(); },
    get length() { return store.size; },
    key: (index: number) => [...store.keys()][index] ?? null,
  };
}

describe('WebStorageTokenStore', () => {
  let storage: Storage;
  let store: WebStorageTokenStore;

  beforeEach(() => {
    storage = createMockStorage();
    store = new WebStorageTokenStore(storage);
  });

  it('returns null when empty', () => {
    expect(store.getToken()).toBeNull();
    expect(store.getPermissions()).toEqual([]);
    expect(store.getExpiresAt()).toBeNull();
  });

  it('isExpired returns true when empty', () => {
    expect(store.isExpired()).toBe(true);
  });

  it('stores and retrieves token', () => {
    store.setToken({ token: 'abc123', permissions: ['read', 'write'], expiresAt: undefined });
    expect(store.getToken()).toBe('abc123');
    expect(store.getPermissions()).toEqual(['read', 'write']);
    expect(store.getExpiresAt()).toBeNull();
  });

  it('persists to storage as JSON', () => {
    store.setToken({ token: 'xyz', permissions: ['admin'] });
    const raw = storage.getItem('hal_token');
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw!);
    expect(parsed.token).toBe('xyz');
    expect(parsed.permissions).toEqual(['admin']);
  });

  it('uses custom key', () => {
    const customStore = new WebStorageTokenStore(storage, 'my_key');
    customStore.setToken({ token: 'tok', permissions: [] });
    expect(storage.getItem('my_key')).not.toBeNull();
    expect(storage.getItem('hal_token')).toBeNull();
  });

  it('clearToken removes from storage', () => {
    store.setToken({ token: 'tok', permissions: [] });
    store.clearToken();
    expect(store.getToken()).toBeNull();
    expect(storage.getItem('hal_token')).toBeNull();
  });

  it('isExpired returns false when no expiresAt', () => {
    store.setToken({ token: 'tok', permissions: [] });
    expect(store.isExpired()).toBe(false);
  });

  it('isExpired returns false when token is still valid', () => {
    store.setToken({ token: 'tok', permissions: [], expiresAt: Date.now() + 120_000 });
    expect(store.isExpired()).toBe(false);
  });

  it('isExpired returns true within 30s margin', () => {
    store.setToken({ token: 'tok', permissions: [], expiresAt: Date.now() + 20_000 });
    expect(store.isExpired()).toBe(true);
  });

  it('isExpired returns true when past expiry', () => {
    store.setToken({ token: 'tok', permissions: [], expiresAt: Date.now() - 1000 });
    expect(store.isExpired()).toBe(true);
  });

  it('handles corrupt JSON gracefully', () => {
    storage.setItem('hal_token', 'not-json');
    expect(store.getToken()).toBeNull();
    expect(store.isExpired()).toBe(true);
  });
});
