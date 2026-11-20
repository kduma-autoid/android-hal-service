/**
 * host = built-in, ext = legacy external, accessory = external NFC reader,
 * subScreen = NFC in an external customer screen.
 */
export type NfcModuleType = 'host' | 'ext' | 'accessory' | 'subScreen' | 'unknown';

export interface NfcModule {
  sn: string;
  /** Absent on older HAL services. */
  type?: NfcModuleType;
  /** Whether this module is the currently active reader. Absent on older HAL services. */
  selected?: boolean;
}

export interface NfcModulesChangedEvent {
  modules: NfcModule[];
}

export const NFC_EVENTS = {
  MODULES_CHANGED: 'sunmi.nfc.modulesChanged',
} as const;

export type NfcErrorCode =
  | 'bad_request'
  | 'unavailable'
  | 'unsupported_method'
  | 'internal_error';
