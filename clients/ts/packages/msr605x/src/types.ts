/** Track number on a magnetic stripe card. */
export type Msr605xTrack = 1 | 2 | 3;

/** LED state: `yellow` lights green and yellow together (device behaviour). */
export type Msr605xLed = 'off' | 'green' | 'yellow' | 'red' | 'all';

/** Write coercivity: Hi-Co or Lo-Co. */
export type Msr605xCoercivity = 'hi' | 'lo';

/** Bits per inch. */
export type Msr605xBpi = 210 | 75;

/** Options for operations that wait for a card swipe. */
export interface Msr605xSwipeOptions {
  /**
   * How long the device waits for a swipe, in ms (default 15000, max 120000). Keep it below the
   * transport's request timeout (30 s by default).
   */
  timeoutMs?: number;
}

/**
 * ISO 7811 read result. Tracks are without sentinels: `""` = empty track, `null` = read error.
 * `status` is `"ok"` or the device's error description.
 */
export interface Msr605xReadResult {
  status: string;
  track1: string | null;
  track2: string | null;
  track3: string | null;
}

/** Raw read result; tracks are hex strings. */
export interface Msr605xRawReadResult {
  status: string;
  track1: string | null;
  track2: string | null;
  track3: string | null;
}

/** ISO tracks to write (data without sentinels); omitted tracks are left untouched. */
export interface Msr605xWriteTracks {
  track1?: string;
  track2?: string;
  track3?: string;
}

/** Raw tracks to write, as hex strings. */
export interface Msr605xRawWriteTracks {
  track1?: string;
  track2?: string;
  track3?: string;
}

export interface Msr605xInfo {
  connected: boolean;
  permission: boolean;
  model?: string | null;
  firmware?: string | null;
}

export interface Msr605xBpiSettings {
  track1?: Msr605xBpi;
  track2?: Msr605xBpi;
  track3?: Msr605xBpi;
}

export interface Msr605xBpcSettings {
  track1: number;
  track2: number;
  track3: number;
}

export interface Msr605xLeadingZeros {
  /** Leading zeros for tracks 1 and 3 (0-255). */
  track13: number;
  /** Leading zeros for track 2 (0-255). */
  track2: number;
}

export const MSR605X_EVENTS = {
  /** A card was read; payload is {@link Msr605xReadResult}. */
  SWIPE: 'msr605x.swipe',
  ATTACHED: 'msr605x.attached',
  DETACHED: 'msr605x.detached',
} as const;

export type Msr605xErrorCode =
  | 'timeout'
  | 'cancelled'
  | 'unavailable'
  | 'bad_request'
  | 'write_failed'
  | 'erase_failed'
  | 'device_error'
  | 'unsupported_method'
  | 'internal_error';
