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
