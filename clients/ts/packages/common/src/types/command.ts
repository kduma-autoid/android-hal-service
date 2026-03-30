export interface ExecuteRequest {
  method: string;
  params?: unknown;
}

export interface ExecuteResponse<T = unknown> {
  result?: T;
  error?: string;
  message?: string;
}
