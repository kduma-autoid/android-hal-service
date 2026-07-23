/**
 * Shared types for the Sunmi light/LED HAL plugins (CPad `sunmi.tms.led` and
 * FLEX `sunmi.statuslight`). Both clients speak the same unified surface.
 */
export const LIGHT_COLORS = ['red', 'green', 'blue', 'yellow', 'cyan', 'magenta', 'white'] as const;

export type LightColor = typeof LIGHT_COLORS[number];

export interface FlashStep {
  color: LightColor;
  onMs: number;
  offMs: number;
}

/** Compact per-step form for multiFlash: [color, onMs, offMs]. */
export type FlashTuple = [LightColor, number, number];

/** A single multiFlash step, as either an object or a [color, onMs, offMs] tuple. */
export type MultiFlashStep = FlashStep | FlashTuple;

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
