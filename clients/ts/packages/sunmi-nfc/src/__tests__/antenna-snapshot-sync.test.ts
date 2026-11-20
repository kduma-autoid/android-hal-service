import { describe, expect, it } from 'vitest';
import {
  isCurrentNfcSnapshotRequest,
  shouldApplyNfcGetterResult,
  type NfcSnapshotAsyncState,
  type NfcSnapshotAsyncRequest,
} from '../antenna-snapshot-sync.js';

const client = {};

function request(overrides: Partial<NfcSnapshotAsyncRequest> = {}): NfcSnapshotAsyncRequest {
  return { generation: 4, eventRevision: 2, client, ...overrides };
}

function state(overrides: Partial<NfcSnapshotAsyncState> = {}): NfcSnapshotAsyncState {
  return { generation: 4, eventRevision: 2, client, ready: true, stale: false, ...overrides };
}

describe('NFC antenna snapshot async guards', () => {
  it('accepts work for the current connected client', () => {
    expect(isCurrentNfcSnapshotRequest(request(), state())).toBe(true);
  });

  it.each([
    ['a newer generation', { generation: 5 }, {}],
    ['a replaced client', {}, { client: {} }],
    ['a disconnected client', {}, { ready: false }],
    ['a stale socket', {}, { stale: true }],
  ])('rejects work after %s', (_description, requestOverrides, stateOverrides) => {
    expect(isCurrentNfcSnapshotRequest(request(requestOverrides), state(stateOverrides))).toBe(false);
  });

  it('rejects a getter result when an event arrived while it was pending', () => {
    expect(shouldApplyNfcGetterResult(request(), state({ eventRevision: 3 }))).toBe(false);
  });

  it('accepts a getter result when no newer event arrived', () => {
    expect(shouldApplyNfcGetterResult(request(), state())).toBe(true);
  });
});
