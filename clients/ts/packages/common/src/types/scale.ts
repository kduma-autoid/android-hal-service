/**
 * Shared types for the `scale` HAL interface (first provider: `dymo.m10`).
 */

/** Unit the scale is set to. */
export type ScaleUnit = 'g' | 'oz';

/**
 * State the scale itself reports. Informational only: some scales (the Dymo M10) say `stable`
 * nearly all the time — use {@link ScaleReading.stable} instead.
 */
export type ScaleStatus =
  | 'zero'
  | 'inMotion'
  | 'stable'
  | 'underZero'
  | 'overWeight'
  | 'fault'
  | 'needCalibration'
  | 'needZeroing'
  | 'unknown'
  /** Only in `scale.onStatus`: the provider lost the scale's readings (off, or USB taken by another app). */
  | 'offline';

/** One reading (`scale.getWeight`, `scale.onWeight`). */
export interface ScaleReading {
  /** Weight in grams, whatever unit the scale is set to; negative below zero. */
  grams: number;
  /** Weight as shown on the scale's display, in {@link unit}. */
  value: number;
  unit: ScaleUnit;
  /** The scale's own status (informational). */
  status: ScaleStatus;
  /**
   * Computed by the provider: the weight has settled (no change of 1 g or more for 500 ms on the
   * M10) and the scale reports no error. Use this to decide when a reading is final.
   */
  stable: boolean;
}

/** Payload of `scale.onStatus`. */
export interface ScaleStatusChange {
  status: ScaleStatus;
  previous: ScaleStatus;
}

/** Result of `scale.getInfo`. */
export interface ScaleInfo {
  name: string;
  connected: boolean;
  /** The latest reading, or null before the first one. */
  reading: ScaleReading | null;
}
