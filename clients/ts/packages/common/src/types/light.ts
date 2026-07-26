/**
 * Shared types for the `light` HAL interface and the Sunmi light/LED plugins behind it
 * (CPad `sunmi.tms.led` and FLEX `sunmi.statuslight`).
 */
export const LIGHT_COLORS = ['red', 'green', 'blue', 'yellow', 'cyan', 'magenta', 'white'] as const;

export type LightColor = typeof LIGHT_COLORS[number];

export interface FlashStep {
  color: LightColor;
  onMs: number;
  offMs: number;
}

export interface LightOptions {
  /**
   * Auto-release the light after this many milliseconds (0 / omitted = stay until turned off).
   * Only honoured by backends whose `capabilities.timeout` is true; other backends throw.
   */
  timeoutMs?: number;
}

export interface LightCapabilities {
  /** Supports `multiFlash()` (cycling through multiple colors). */
  multiFlash: boolean;
  /** Supports the `timeoutMs` option on `on()` / `flash()`. */
  timeout: boolean;
}

/**
 * Overloaded signature for a light client's `multiFlash`: accept either an explicit list of
 * per-step objects `{color, onMs, offMs}`, or a list of colors with uniform timing. Present only on
 * backends whose `capabilities.multiFlash` is true.
 */
export interface MultiFlash {
  (steps: FlashStep[]): Promise<void>;
  (colors: LightColor[], onMs: number, offMs: number): Promise<void>;
}
