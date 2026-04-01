export interface NfcModule {
  sn: string;
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
