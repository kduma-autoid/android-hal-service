export interface ScreenDeviceInfo {
  info: Record<string, unknown>;
}

export interface ScreensChangedEvent {
  sn: string;
  type: number;
  value: number;
  extra: string;
}

export const SCREEN_EVENTS = {
  SCREENS_CHANGED: 'sunmi.screen.screensChanged',
} as const;

export type ScreenErrorCode =
  | 'bad_request'
  | 'unavailable'
  | 'unsupported_method'
  | 'internal_error';
