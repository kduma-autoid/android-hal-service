import type { ITokenStore, ILogger } from '@kduma-autoid/hal-client-common';

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
  serviceKey?: string;
  requestedPermissions?: string[];
  tokenStore?: ITokenStore;
  logger?: ILogger;
  onTokenExpired?: () => void;
  autoReconnect?: boolean;
  maxReconnectAttempts?: number;
  timeout?: number;
}

/** Default service host/port used when neither `baseUrl` nor `host`/`port` is provided. */
export const DEFAULT_HOST = 'localhost';
export const DEFAULT_PORT = 8400;

/**
 * Resolves the HTTP base URL from client options: an explicit `baseUrl` wins, otherwise it is
 * composed from `host`/`port` (falling back to the defaults).
 */
export function resolveBaseUrl(options: HalClientOptions): string {
  if (options.baseUrl) return options.baseUrl;
  const scheme = options.secure ? 'https' : 'http';
  const host = options.host ?? DEFAULT_HOST;
  const port = options.port ?? DEFAULT_PORT;
  return `${scheme}://${host}:${port}`;
}

/**
 * Resolves the WebSocket URL from client options: derives it from the HTTP base URL, mapping
 * `http`/`https` to `ws`/`wss` and appending the `/ws` path (using the URL API rather than string
 * surgery so schemes, ports and existing paths are handled correctly).
 */
export function resolveWsUrl(options: HalClientOptions): string {
  const url = new URL(resolveBaseUrl(options));
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.pathname = url.pathname.replace(/\/+$/, '') + '/ws';
  return url.toString();
}
