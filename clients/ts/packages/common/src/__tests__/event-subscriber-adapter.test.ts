import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { IEventTransport } from '../interfaces/event-transport.js';
import { EventSubscriberAdapter } from '../event-subscriber-adapter.js';

function createMockTransport(): IEventTransport {
  return {
    subscribe: vi.fn().mockResolvedValue(undefined),
    unsubscribe: vi.fn().mockResolvedValue(undefined),
    on: vi.fn().mockReturnValue(() => {}),
    off: vi.fn(),
    dispose: vi.fn(),
    setToken: vi.fn(),
  };
}

describe('EventSubscriberAdapter', () => {
  let transport: IEventTransport;
  let adapter: EventSubscriberAdapter;

  beforeEach(() => {
    transport = createMockTransport();
    adapter = new EventSubscriberAdapter(transport);
  });

  it('should subscribe on first handler for an event', async () => {
    await adapter.on('sunmi.nfc.modulesChanged', () => {});

    expect(transport.subscribe).toHaveBeenCalledWith(['sunmi.nfc.modulesChanged']);
    expect(transport.on).toHaveBeenCalledWith('sunmi.nfc.modulesChanged', expect.any(Function));
  });

  it('should not subscribe again for second handler on same event', async () => {
    await adapter.on('sunmi.nfc.modulesChanged', () => {});
    await adapter.on('sunmi.nfc.modulesChanged', () => {});

    expect(transport.subscribe).toHaveBeenCalledTimes(1);
    expect(transport.on).toHaveBeenCalledTimes(2);
  });

  it('should subscribe separately for different events', async () => {
    await adapter.on('sunmi.nfc.modulesChanged', () => {});
    await adapter.on('sunmi.screen.screensChanged', () => {});

    expect(transport.subscribe).toHaveBeenCalledTimes(2);
    expect(transport.subscribe).toHaveBeenCalledWith(['sunmi.nfc.modulesChanged']);
    expect(transport.subscribe).toHaveBeenCalledWith(['sunmi.screen.screensChanged']);
  });

  it('should unsubscribe when last handler is removed', async () => {
    const unsub = await adapter.on('sunmi.nfc.modulesChanged', () => {});

    await unsub();

    expect(transport.unsubscribe).toHaveBeenCalledWith(['sunmi.nfc.modulesChanged']);
  });

  it('should not unsubscribe when other handlers remain', async () => {
    const unsub1 = await adapter.on('sunmi.nfc.modulesChanged', () => {});
    await adapter.on('sunmi.nfc.modulesChanged', () => {});

    await unsub1();

    expect(transport.unsubscribe).not.toHaveBeenCalled();
  });

  it('should unsubscribe after all handlers removed', async () => {
    const unsub1 = await adapter.on('sunmi.nfc.modulesChanged', () => {});
    const unsub2 = await adapter.on('sunmi.nfc.modulesChanged', () => {});

    await unsub1();
    expect(transport.unsubscribe).not.toHaveBeenCalled();

    await unsub2();
    expect(transport.unsubscribe).toHaveBeenCalledWith(['sunmi.nfc.modulesChanged']);
  });

  it('should call transport off() for the specific handler', async () => {
    const offFn = vi.fn();
    (transport.on as ReturnType<typeof vi.fn>).mockReturnValue(offFn);

    const unsub = await adapter.on('sunmi.nfc.modulesChanged', () => {});
    await unsub();

    expect(offFn).toHaveBeenCalled();
  });

  it('should re-subscribe after all handlers removed and new one added', async () => {
    const unsub = await adapter.on('sunmi.nfc.modulesChanged', () => {});
    await unsub();

    expect(transport.subscribe).toHaveBeenCalledTimes(1);
    expect(transport.unsubscribe).toHaveBeenCalledTimes(1);

    await adapter.on('sunmi.nfc.modulesChanged', () => {});

    expect(transport.subscribe).toHaveBeenCalledTimes(2);
  });
});
