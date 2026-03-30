import { WebStorageTokenStore } from './web-storage-token-store.js';

export class SessionStorageTokenStore extends WebStorageTokenStore {
  constructor(key = 'hal_token') {
    super(globalThis.sessionStorage, key);
  }
}
