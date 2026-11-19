import { describe, it, expect, vi } from 'vitest';
import type { IHalClient } from '@kduma-autoid/hal-client-common';
import { SunmiCardClient, PLUGIN_ID } from '../sunmi-card-client.js';
import { SUNMI_CARD_EVENTS } from '../types.js';

function createMockClient(reply: unknown = {}): IHalClient {
  return {
    execute: vi.fn().mockResolvedValue(reply),
    on: vi.fn().mockResolvedValue(vi.fn().mockResolvedValue(undefined)),
  } as unknown as IHalClient;
}

describe('SunmiCardClient', () => {
  it('uses the sunmi.card plugin id', () => {
    expect(PLUGIN_ID).toBe('sunmi.card');
  });

  it('getInfo calls sunmi.card.getInfo', async () => {
    const info = { name: 'MSR Reader', version: '1.0', sn: 'MSR-001', connected: true };
    const mock = createMockClient(info);
    await expect(new SunmiCardClient(mock).getInfo()).resolves.toEqual(info);
    expect(mock.execute).toHaveBeenCalledWith('sunmi.card.getInfo', {});
  });

  it('onSwipe subscribes to sunmi.card.swipe and forwards the payload', async () => {
    const mock = createMockClient();
    const handler = vi.fn();
    await new SunmiCardClient(mock).onSwipe(handler);
    expect(mock.on).toHaveBeenCalledWith(SUNMI_CARD_EVENTS.SWIPE, expect.any(Function));

    const cb = vi.mocked(mock.on).mock.calls[0][1] as (name: string, data: unknown) => void;
    const swipe = { raw: '%B4111^DOE/JOHN^2512?;4111=2512?', track1: '%B4111^DOE/JOHN^2512?', track2: ';4111=2512?', track3: null };
    cb('sunmi.card.swipe', swipe);
    expect(handler).toHaveBeenCalledWith(swipe);
  });
});
