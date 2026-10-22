export interface NfcSnapshotAsyncState {
  generation: number;
  eventRevision: number;
  client: unknown;
  ready: boolean;
  stale: boolean;
}

export interface NfcSnapshotAsyncRequest {
  generation: number;
  eventRevision: number;
  client: unknown;
}

/** Guards work that may resume after a client replacement or connection loss. */
export function isCurrentNfcSnapshotRequest(
  request: NfcSnapshotAsyncRequest,
  state: NfcSnapshotAsyncState,
): boolean {
  return request.generation === state.generation &&
    request.client === state.client &&
    state.ready &&
    !state.stale;
}

/** A getter result must not replace an event snapshot received while it was in flight. */
export function shouldApplyNfcGetterResult(
  request: NfcSnapshotAsyncRequest,
  state: NfcSnapshotAsyncState,
): boolean {
  return isCurrentNfcSnapshotRequest(request, state) &&
    request.eventRevision === state.eventRevision;
}
