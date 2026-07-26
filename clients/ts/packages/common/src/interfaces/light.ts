import type {
  FlashStep,
  LightCapabilities,
  LightColor,
  LightOptions,
} from '../types/light.js';

/**
 * Overloaded signature for {@link ILight.multiFlash}: accept either an explicit list of
 * per-step objects `{color, onMs, offMs}`, or a list of colors with uniform timing.
 */
export interface MultiFlash {
  (steps: FlashStep[]): Promise<void>;
  (colors: LightColor[], onMs: number, offMs: number): Promise<void>;
}

/**
 * Unified control surface for a light/LED backend.
 *
 * Implemented by `SunmiLightClient`, which routes `light.*` through the server-side `light`
 * interface and resolves a provider (CPad LED, FLEX status light, …), so callers program against
 * a single type and the backend is transparent. The plugin-specific clients
 * (`SunmiTmsLedClient`, `SunmiStatusLightClient`) deliberately do NOT implement this type — they
 * call their own `sunmi.*` methods directly and bypass interface routing.
 *
 * Feature differences are advertised via {@link LightCapabilities} rather than by diverging
 * method sets; the flags are derived from the resolved provider's advertised interface features:
 *  - `timeoutMs` is honoured only when `capabilities.timeout` is true (else throws).
 *  - `multiFlash()` is present only when `capabilities.multiFlash` is true.
 *
 * Availability of a backend (whether the hardware is present) is not queried here — it is
 * reflected by whether the plugin is advertised as an available provider of the `light`
 * interface in `system.describe`.
 */
export interface ILight {
  /** Static description of what this backend supports. */
  readonly capabilities: LightCapabilities;

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
