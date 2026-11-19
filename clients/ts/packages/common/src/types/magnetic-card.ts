/**
 * Shared types for the `magneticCardReader` HAL interface. Active readers/writers advertise the
 * `read` and `write` features; passive readers only report swipes via `magneticCardReader.onSwipe`.
 *
 * Track data never carries sentinels (`%`/`;` … `?`). In a read result or event a track is its data,
 * `""` when the track is empty, or `null` when it could not be read.
 */

/** Optional operations a magnetic card reader backend may advertise. */
export const MAGNETIC_CARD_READER_FEATURES = ['read', 'write'] as const;

export type MagneticCardReaderFeature = typeof MAGNETIC_CARD_READER_FEATURES[number];

/** Which optional operations the bound magnetic card reader backend supports. */
export interface MagneticCardReaderCapabilities {
  /** On-demand reads (`read` / `cancel`); passive readers only emit `onSwipe`. */
  read: boolean;
  /** Card encoding (`write`). */
  write: boolean;
}

/** The three ISO 7811 tracks of a swiped card. */
export interface MagneticCardTracks {
  track1: string | null;
  track2: string | null;
  track3: string | null;
}

/** Tracks to encode (data without sentinels). Omitted tracks are left untouched. */
export interface MagneticCardWrite {
  track1?: string;
  track2?: string;
  track3?: string;
}

/** Options for operations that wait for a card swipe. */
export interface MagneticCardSwipeOptions {
  /**
   * How long the device waits for a swipe, in ms (backend default 15000). Keep it below the
   * transport's request timeout (30 s by default) or the call times out client-side first.
   */
  timeoutMs?: number;
}

/** Result of `magneticCardReader.getInfo`. */
export interface MagneticCardReaderInfo {
  name: string;
  connected: boolean;
  model?: string | null;
  firmware?: string | null;
}
