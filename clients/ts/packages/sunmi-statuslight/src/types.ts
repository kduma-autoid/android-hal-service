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

export type StatusLightErrorCode =
  | 'bad_request'
  | 'unavailable'
  | 'unsupported_method'
  | 'internal_error';
