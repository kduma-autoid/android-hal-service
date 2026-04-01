export interface TokenRequest {
  clientId: string;
  serviceKey?: string;
  requestedPermissions?: string[];
}

export interface TokenResult {
  token: string;
  permissions: string[];
  expiresAt?: number;
}
