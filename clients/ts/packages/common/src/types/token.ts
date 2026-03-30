export interface TokenRequest {
  clientId: string;
  developerKey?: string;
  requestedPermissions?: string[];
}

export interface TokenResult {
  token: string;
  permissions: string[];
  expiresAt?: number;
}
