import type { FlashStep, LightCapabilities, LightColor, LightOptions } from '../types/light.js';

/**
 * Overloaded signature for {@link ILight.multiFlash}: accept either an explicit
 * list of steps (per-step timing) or a list of colors with uniform timing.
 */
export interface MultiFlash {
  (steps: FlashStep[]): Promise<void>;
  (colors: LightColor[], onMs: number, offMs: number): Promise<void>;
}

/**
 * Unified control surface for a Sunmi light/LED backend.
 *
 * Implemented by both `SunmiTmsLedClient` (CPad) and `SunmiStatusLightClient` (FLEX),
 * so callers can program against a single type and switch backends transparently.
 * Feature differences are advertised via {@link LightCapabilities} rather than by
 * diverging method sets:
 *  - `timeoutMs` is honoured only when `capabilities.timeout` is true (else throws).
 *  - `multiFlash()` is present only when `capabilities.multiFlash` is true.
 */
export interface ILight {
  /** Static description of what this backend supports. */
  readonly capabilities: LightCapabilities;

  /** Returns true if the device actually has a controllable light/LED. */
  isSupported(): Promise<boolean>;

  /** Turns the light off. */
  off(): Promise<void>;

  /** Turns the light on with a steady color. */
  on(color: LightColor, options?: LightOptions): Promise<void>;

  /** Blinks the light in a single color. */
  flash(color: LightColor, onMs: number, offMs: number, options?: LightOptions): Promise<void>;

  /**
   * Cycles the light through multiple colors. Present only when
   * `capabilities.multiFlash` is true. Accepts either explicit steps or a
   * color list with uniform timing.
   */
  multiFlash?: MultiFlash;
}
