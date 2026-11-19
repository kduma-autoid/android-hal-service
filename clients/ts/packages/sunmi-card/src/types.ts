/** Result of `sunmi.card.getInfo`. */
export interface SunmiCardInfo {
  name: string;
  version: string;
  sn: string;
  connected: boolean;
}

/**
 * Payload of `sunmi.card.swipe`: the raw reader output and the tracks parsed from it. Unlike the
 * `magneticCardReader` interface, these tracks keep their sentinels (`%…?`, `;…?`); a track the
 * card does not carry is `null`.
 */
export interface SunmiCardSwipeEvent {
  raw: string;
  track1: string | null;
  track2: string | null;
  track3: string | null;
}

export const SUNMI_CARD_EVENTS = {
  SWIPE: 'sunmi.card.swipe',
} as const;

export type SunmiCardErrorCode =
  | 'unavailable'
  | 'unsupported_method'
  | 'internal_error';
