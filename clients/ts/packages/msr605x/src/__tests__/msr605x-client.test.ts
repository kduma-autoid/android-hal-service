import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { Msr605xClient } from '../msr605x-client.js';
import { MSR605X_EVENTS } from '../types.js';

function createMockClient(reply: unknown = {}): IHalClient {
  return {
    execute: vi.fn().mockResolvedValue(reply),
    on: vi.fn().mockResolvedValue(vi.fn().mockResolvedValue(undefined)),
  } as unknown as IHalClient;
}

describe('Msr605xClient', () => {
  let mock: IHalClient;
  let msr: Msr605xClient;

  beforeEach(() => {
    mock = createMockClient();
    msr = new Msr605xClient(mock);
  });

  it.each([
    ['read', () => msr.read({ timeoutMs: 5000 }), 'msr605x.read', { timeoutMs: 5000 }],
    ['read (defaults)', () => msr.read(), 'msr605x.read', {}],
    ['readRaw', () => msr.readRaw(), 'msr605x.readRaw', {}],
    [
      'write',
      () => msr.write({ track1: 'ABC', track2: '123' }, { timeoutMs: 9000 }),
      'msr605x.write',
      { track1: 'ABC', track2: '123', timeoutMs: 9000 },
    ],
    ['writeRaw', () => msr.writeRaw({ track1: 'a2c4' }), 'msr605x.writeRaw', { track1: 'a2c4' }],
    ['erase', () => msr.erase([1, 3]), 'msr605x.erase', { tracks: [1, 3] }],
    ['setCoercivity', () => msr.setCoercivity('hi'), 'msr605x.setCoercivity', { mode: 'hi' }],
    ['setBpi', () => msr.setBpi({ track1: 210, track2: 75 }), 'msr605x.setBpi', { track1: 210, track2: 75 }],
    [
      'setBpc',
      () => msr.setBpc({ track1: 7, track2: 5, track3: 5 }),
      'msr605x.setBpc',
      { track1: 7, track2: 5, track3: 5 },
    ],
    [
      'setLeadingZeros',
      () => msr.setLeadingZeros({ track13: 61, track2: 22 }),
      'msr605x.setLeadingZeros',
      { track13: 61, track2: 22 },
    ],
    ['getLeadingZeros', () => msr.getLeadingZeros(), 'msr605x.getLeadingZeros', {}],
    ['getInfo', () => msr.getInfo(), 'msr605x.getInfo', {}],
    ['setLed', () => msr.setLed('green'), 'msr605x.setLed', { led: 'green' }],
    ['reset (soft)', () => msr.reset(), 'msr605x.reset', { hard: false }],
    ['reset (hard)', () => msr.reset({ hard: true }), 'msr605x.reset', { hard: true }],
  ] as const)('%s calls %s', async (_label, call, method, params) => {
    await call();
    expect(mock.execute).toHaveBeenCalledWith(method, params);
  });

  it('getCoercivity unwraps the mode', async () => {
    mock = createMockClient({ mode: 'lo' });
    await expect(new Msr605xClient(mock).getCoercivity()).resolves.toBe('lo');
  });

  it('getCoercivity maps an unknown setting to null', async () => {
    mock = createMockClient({ mode: null });
    await expect(new Msr605xClient(mock).getCoercivity()).resolves.toBeNull();
  });

  it('cancel and commTest unwrap their flags', async () => {
    mock = createMockClient({ cancelled: true, ok: true });
    const c = new Msr605xClient(mock);
    await expect(c.cancel()).resolves.toBe(true);
    expect(mock.execute).toHaveBeenCalledWith('msr605x.cancel', {});
    await expect(c.commTest()).resolves.toBe(true);
    expect(mock.execute).toHaveBeenCalledWith('msr605x.commTest', {});
  });

  it('setListening sends the flag and unwraps the state', async () => {
    mock = createMockClient({ listening: false });
    const c = new Msr605xClient(mock);
    await expect(c.setListening(false)).resolves.toBe(false);
    expect(mock.execute).toHaveBeenCalledWith('msr605x.setListening', { enabled: false });
  });

  it('subscribes to native events', async () => {
    const handler = vi.fn();
    await msr.onSwipe(handler);
    await msr.onAttached(vi.fn());
    await msr.onDetached(vi.fn());
    expect(mock.on).toHaveBeenCalledWith(MSR605X_EVENTS.SWIPE, expect.any(Function));
    expect(mock.on).toHaveBeenCalledWith('msr605x.attached', expect.any(Function));
    expect(mock.on).toHaveBeenCalledWith('msr605x.detached', expect.any(Function));

    const cb = vi.mocked(mock.on).mock.calls[0][1] as (name: string, data: unknown) => void;
    const result = { status: 'ok', track1: 'ABC', track2: '', track3: null };
    cb('msr605x.swipe', result);
    expect(handler).toHaveBeenCalledWith(result);
  });
});
