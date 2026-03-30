import { WebStorageTokenStore } from './web-storage-token-store.js';

export class LocalStorageTokenStore extends WebStorageTokenStore {
  constructor(key = 'hal_token') {
    super(globalThis.localStorage, key);
  }
}
