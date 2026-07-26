import { describe, it, expect } from 'vitest';
import { matchPattern, matchSubscription } from '../utils/pattern-matcher.js';

describe('matchPattern', () => {
  it('global wildcard matches everything', () => {
    expect(matchPattern('*', 'scanner.barcode')).toBe(true);
    expect(matchPattern('*', 'printer.status')).toBe(true);
    expect(matchPattern('*', 'anything')).toBe(true);
  });

  it('exact match', () => {
    expect(matchPattern('scanner.barcode', 'scanner.barcode')).toBe(true);
    expect(matchPattern('scanner.barcode', 'scanner.status')).toBe(false);
    expect(matchPattern('scanner.barcode', 'printer.barcode')).toBe(false);
  });

  it('prefix wildcard matches events starting with prefix', () => {
    expect(matchPattern('scanner.*', 'scanner.barcode')).toBe(true);
    expect(matchPattern('scanner.*', 'scanner.status')).toBe(true);
    expect(matchPattern('scanner.*', 'scanner.config.update')).toBe(true);
    expect(matchPattern('scanner.*', 'printer.status')).toBe(false);
  });

  it('prefix wildcard requires dot separator', () => {
    // "rfid.*" should match "rfid.tag_read" but not "rfid_extra"
    expect(matchPattern('rfid.*', 'rfid.tag_read')).toBe(true);
    expect(matchPattern('rfid.*', 'rfid_extra')).toBe(false);
  });

  it('non-matching patterns', () => {
    expect(matchPattern('printer.status', 'scanner.status')).toBe(false);
    expect(matchPattern('printer.*', 'scanner.barcode')).toBe(false);
  });

  it('empty event name', () => {
    expect(matchPattern('*', '')).toBe(true);
    expect(matchPattern('scanner.*', '')).toBe(false);
    expect(matchPattern('', '')).toBe(true);
  });
});

describe('matchSubscription', () => {
  it('with no @source behaves like matchPattern (any emitter)', () => {
    expect(matchSubscription('demo.notice', 'demo.notice', 'demo.alpha')).toBe(true);
    expect(matchSubscription('scanner.*', 'scanner.barcode', 'sunmi.inner')).toBe(true);
    expect(matchSubscription('demo.notice', 'demo.notice')).toBe(true);
    expect(matchSubscription('demo.notice', 'demo.other', 'demo.alpha')).toBe(false);
  });

  it('with @source matches only the given emitter', () => {
    expect(matchSubscription('demo.notice@demo.beta', 'demo.notice', 'demo.beta')).toBe(true);
    expect(matchSubscription('demo.notice@demo.beta', 'demo.notice', 'demo.alpha')).toBe(false);
  });

  it('supports wildcards on both halves', () => {
    expect(matchSubscription('scanner.*@sunmi.*', 'scanner.barcode', 'sunmi.inner')).toBe(true);
    expect(matchSubscription('scanner.*@sunmi.*', 'scanner.barcode', 'honeywell.ext')).toBe(false);
    expect(matchSubscription('scanner.*@sunmi.*', 'rfid.tag', 'sunmi.rfid')).toBe(false);
  });

  it('a source filter with a missing source does not match', () => {
    expect(matchSubscription('demo.notice@demo.beta', 'demo.notice', undefined)).toBe(false);
  });
});
