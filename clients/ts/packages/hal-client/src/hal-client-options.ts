import type { ITokenStore, ILogger } from '@kduma-autoid/hal-client-common';

export interface HalClientOptions {
  clientId: string;
  /** Full base URL, e.g. `http://localhost:8400`. Takes precedence over `host`/`port`. */
  baseUrl?: string;
  /** Service address/host. Used to build the base URL when `baseUrl` is not set. Default `localhost`. */
  host?: string;
  /** Service port. Used to build the base URL when `baseUrl` is not set. Default `8400`. */
  port?: number;
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
  const host = options.host ?? DEFAULT_HOST;
  const port = options.port ?? DEFAULT_PORT;
  return `http://${host}:${port}`;
}
