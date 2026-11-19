import type { ScaleReading, ScaleStatusChange } from '@kduma-autoid/hal-client-common';

/** Result of `dymo.m10.getInfo`. */
export interface DymoM10Info {
  name: string;
  /** The scale is attached and its USB connection is open. */
  connected: boolean;
  /** The service holds USB permission for the scale. */
  permission: boolean;
  /** The latest reading, or null before the first one. */
  reading: ScaleReading | null;
}

export type DymoM10Reading = ScaleReading;
export type DymoM10StatusChange = ScaleStatusChange;

export const DYMO_M10_EVENTS = {
  /** A new reading (moved / settled / status or unit changed); payload is {@link DymoM10Reading}. */
  WEIGHT: 'dymo.m10.weight',
  /** The scale's own status changed; payload is {@link DymoM10StatusChange}. */
  STATUS: 'dymo.m10.status',
  ATTACHED: 'dymo.m10.attached',
  DETACHED: 'dymo.m10.detached',
} as const;
