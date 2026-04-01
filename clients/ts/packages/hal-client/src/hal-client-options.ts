import type { ITokenStore, ILogger } from '@kduma-autoid/hal-client-common';

export interface HalClientOptions {
  clientId: string;
  baseUrl?: string;
  serviceKey?: string;
  requestedPermissions?: string[];
  tokenStore?: ITokenStore;
  logger?: ILogger;
  onTokenExpired?: () => void;
  autoReconnect?: boolean;
  maxReconnectAttempts?: number;
  timeout?: number;
}
