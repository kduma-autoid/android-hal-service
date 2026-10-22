export { SunmiNfcClient, PLUGIN_ID } from './sunmi-nfc-client.js';
export { NFC_EVENTS } from './types.js';
export {
  isCurrentNfcSnapshotRequest,
  shouldApplyNfcGetterResult,
} from './antenna-snapshot-sync.js';
export type {
  NfcSnapshotAsyncState,
  NfcSnapshotAsyncRequest,
} from './antenna-snapshot-sync.js';
export type {
  NfcModule,
  NfcModulesChangedEvent,
  NfcAntennaLocationsState,
  NfcAntennaController,
  NfcAntennaRotationDegrees,
  NfcLocationUnavailableReason,
  NfcAntennaRect,
  NfcAntennaLocation,
  NfcAntennaModule,
  NfcAntennaLocationsSnapshot,
  NfcErrorCode,
} from './types.js';
