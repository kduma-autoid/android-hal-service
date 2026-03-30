export interface IExecutor {
  execute<T = unknown>(method: string, params?: unknown): Promise<T>;
}

export const STATUS_LIGHT_COLORS = ['red', 'green', 'blue', 'yellow', 'magenta', 'cyan', 'white'] as const;

export type StatusLightColor = typeof STATUS_LIGHT_COLORS[number];

export interface FlashStep {
  color: StatusLightColor;
  onMs: number;
  offMs: number;
}

export interface StatusLightResponse {
  status: 'ok';
}

export type StatusLightErrorCode =
  | 'invalid_color'
  | 'device_not_ready'
  | 'invalid_params'
  | 'sdk_error'
  | 'unsupported_method';
