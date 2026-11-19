import type { ITokenStore, ILogger } from '@kduma-autoid/hal-client-common';
import type { ServiceKeyProvider } from './token-manager.js';

export interface HalClientOptions {
  clientId: string;
  /** Full base URL, e.g. `http://localhost:8400`. Takes precedence over `host`/`port`. */
  baseUrl?: string;
  /** Service address/host. Used to build the base URL when `baseUrl` is not set. Default `localhost`. */
  host?: string;
  /** Service port. Used to build the base URL when `baseUrl` is not set. Default `8400`. */
  port?: number;
  /** Use TLS (`https`/`wss`) when building the URL from `host`/`port`. Default `false`. */
  secure?: boolean;
  /**
   * Service key (JWT) for token requests, or a function returning one per request — e.g. signing a
   * fresh device-key JWT, so automatic renewal never presents an expired key. With a service key, an
   * expired or rejected token is renewed automatically and the request retried.
   */
  serviceKey?: ServiceKeyProvider;
  requestedPermissions?: string[];
  tokenStore?: ITokenStore;
  logger?: ILogger;
  /** Called when the token expires or is rejected and cannot be renewed automatically. */
  onTokenExpired?: () => void;
  /**
   * Without a service key, renew a rejected token by requesting a new one automatically — this shows
   * the permission dialog on the device unless a still-valid grant exists. Default `false`: the auth
   * state turns `expired` and the app decides when to call `requestToken()`.
   */
  autoRequestToken?: boolean;
  /** WebSocket: reconnect after the connection is lost. Default `true`. */
  autoReconnect?: boolean;
  /** WebSocket: reconnect attempts before giving up. Default: unlimited. */
  maxReconnectAttempts?: number;
  /** WebSocket: upper bound of the reconnect backoff, in ms. Default 30000. */
  maxReconnectDelay?: number;
  /** WebSocket: probe a silent connection after this many ms (`0` disables). Default 10000. */
  heartbeatInterval?: number;
  /** WebSocket: how long a probe may wait for a reply before the connection counts as lost. Default 5000. */
  heartbeatTimeout?: number;
  timeout?: number;
  /**
   * Timeout for token requests, in ms. Without a service key the service shows a permission dialog and
   * waits up to 60 s for an answer, so this is longer than `timeout`. Default 75000.
   */
  tokenRequestTimeout?: number;
}

/** Default service host/port used when neither `baseUrl` nor `host`/`port` is provided. */
export const DEFAULT_HOST = 'localhost';
export const DEFAULT_PORT = 8400;

/**
 * Resolves the service URL for the given protocol family (`http`/`https` or `ws`/`wss`).
 * An explicit `baseUrl` wins (its scheme is mapped for the `ws` family); otherwise the URL is
 * composed from `host`/`port`/`secure`, falling back to the defaults.
 */
export function resolveBaseUrl(options: HalClientOptions, protocol: 'http' | 'ws' = 'http'): string {
  if (options.baseUrl) {
    return protocol === 'ws' ? options.baseUrl.replace(/^http/, 'ws') : options.baseUrl;
  }
  const scheme = options.secure ? `${protocol}s` : protocol;
  const host = options.host ?? DEFAULT_HOST;
  const port = options.port ?? DEFAULT_PORT;
  return `${scheme}://${host}:${port}`;
}
