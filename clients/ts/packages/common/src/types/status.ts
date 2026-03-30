export interface StatusResponse {
  uptime: number;
  plugins: Record<string, PluginStatus>;
  transports: Record<string, TransportStatus>;
}

export interface PluginStatus {
  version: number;
  capabilities: string[];
  source: string;
  package?: string;
}

export interface TransportStatus {
  running: boolean;
  toggleable?: boolean;
  enabled?: boolean;
}
