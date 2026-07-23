/**
 * Preset colors for the CPad built-in RGB LED indicator.
 *
 * IMPORTANT: the order here matches the Sunmi CPad preset index (1-based):
 * 1=red, 2=green, 3=blue, 4=yellow, 5=cyan, 6=magenta, 7=white.
 * The service maps colors by NAME, so the array order is informational only.
 */
export const TMS_LED_COLORS = ['red', 'green', 'blue', 'yellow', 'cyan', 'magenta', 'white'] as const;

export type TmsLedColor = typeof TMS_LED_COLORS[number];

/** 0 = steady on, 1 = blink (onMs/offMs apply). */
export type TmsLedLightMode = 0 | 1;

export interface TmsLedOpenOptions {
  /** Preset color name or index 1-7. */
  color: TmsLedColor | number;
  /** 0 = steady on (default), 1 = blink. */
  lightMode?: TmsLedLightMode;
  /** Light-on duration in ms (blink mode only). */
  onMs?: number;
  /** Light-off duration in ms (blink mode only). */
  offMs?: number;
  /** Auto-release LED control after this many ms (>0). 0 = no auto timeout. */
  timeoutMs?: number;
}

export type TmsLedErrorCode =
  | 'bad_request'
  | 'unavailable'
  | 'unsupported_method'
  | 'internal_error';
