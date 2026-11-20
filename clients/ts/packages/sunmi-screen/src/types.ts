export interface ScreenDeviceInfo {
  info: Record<string, unknown>;
}

/** option = screen parameter, dcFlag = data-channel flag report. */
export type ScreenEventType = 'option' | 'dcFlag' | 'unknown';

export type ScreenParameter =
  | 'brightness'
  | 'volume'
  | 'screenSwitch'
  | 'cpuTest'
  | 'ddrTest'
  | 'memTest'
  | 'nfcSwitch'
  | 'touchSwitch'
  | 'powerOff'
  | 'tpTest'
  | 'nickname'
  | 'nicknameTransparency'
  | 'nicknameShow'
  | 'colorTemperature'
  | 'screenLog'
  | 'workMode'
  | 'tpLog'
  | 'reboot'
  | 'dcFlag'
  | 'unknown';

/**
 * A parameter value reported by a customer screen — either the ack of a
 * set call or a change the Sunmi service made itself.
 */
export interface ScreensChangedEvent {
  /** Serial number of the customer screen. */
  sn: string;
  type: ScreenEventType;
  /** Parameter id (option) or flag index (dcFlag). */
  key: number;
  /** Decoded name of `key`. */
  parameter: ScreenParameter;
  /** Reported value, e.g. 0/1 for switches, 0-100 for brightness. */
  value: number;
  /** Optional string payload (e.g. nickname); usually empty. */
  str: string;
}

export const SCREEN_EVENTS = {
  SCREENS_CHANGED: 'sunmi.screen.screensChanged',
} as const;

export type ScreenErrorCode =
  | 'bad_request'
  | 'unavailable'
  | 'unsupported_method'
  | 'internal_error';
