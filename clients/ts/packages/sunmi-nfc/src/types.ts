export interface NfcModule {
  sn: string;
}

export interface NfcModulesChangedEvent {
  modules: NfcModule[];
}

export type NfcAntennaLocationsState = 'initializing' | 'ready' | 'unavailable';
export type NfcAntennaController = 'builtin' | 'external' | 'unknown';
export type NfcAntennaRotationDegrees = 0 | 90 | 180 | 270;
export type NfcLocationUnavailableReason =
  | 'external_location_unknown'
  | 'reader_identity_unknown'
  | 'missing_location'
  | 'invalid_location'
  | 'settings_unreadable'
  | 'display_unavailable'
  | 'unsupported_display_metrics';

export interface NfcAntennaRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface NfcAntennaLocation {
  displayId: number;
  displayWidthPx: number;
  displayHeightPx: number;
  rotationDegrees: NfcAntennaRotationDegrees;
  rect: NfcAntennaRect;
  /** True when the natural-coordinate interpretation was necessarily inferred. */
  estimated: boolean;
}

export interface NfcAntennaModule {
  sn: string;
  sdkType: number;
  selected: boolean;
  reader: NfcAntennaController;
  location: NfcAntennaLocation | null;
  locationUnavailableReason: NfcLocationUnavailableReason | null;
}

export interface NfcAntennaLocationsSnapshot {
  state: NfcAntennaLocationsState;
  controller: NfcAntennaController;
  modules: NfcAntennaModule[];
}

export const NFC_EVENTS = {
  MODULES_CHANGED: 'sunmi.nfc.modulesChanged',
  ANTENNA_LOCATIONS_CHANGED: 'sunmi.nfc.antennaLocationsChanged',
} as const;

export type NfcErrorCode =
  | 'bad_request'
  | 'unavailable'
  | 'unsupported_method'
  | 'internal_error';
