import { describe, it, expect, vi } from 'vitest';
import { createApp, defineComponent, h } from 'vue';
import { usePageLifetime } from '../usePageLifetime';

/** Mounts a component that exposes its page lifetime; returns it with an unmount function. */
function mountPage() {
  let page!: ReturnType<typeof usePageLifetime>;
  const app = createApp(
    defineComponent({
      setup() {
        page = usePageLifetime();
        return () => h('div');
      },
    }),
  );
  app.mount(document.createElement('div'));
  return { page, unmount: () => app.unmount() };
}

/** A subscribe that resolves when told to, as a server round trip does. */
function pendingSubscribe() {
  const off = vi.fn(async () => {});
  let resolve!: () => void;
  const promise = new Promise<typeof off>((r) => {
    resolve = () => r(off);
  });
  return { off, promise, resolve };
}

describe('usePageLifetime', () => {
  it('keeps a subscription that arrives while the page is alive', async () => {
    const { page, unmount } = mountPage();
    const s = pendingSubscribe();
    const kept = page.keep(s.promise);
    s.resolve();

    expect(await kept).toBe(s.off);
    expect(s.off).not.toHaveBeenCalled();
    expect(page.isAlive()).toBe(true);
    unmount();
  });

  it('releases a subscription that arrives after the page is gone', async () => {
    // Leaving the page while it still binds: cleanup ran with nothing to release, then this arrived.
    const { page, unmount } = mountPage();
    const s = pendingSubscribe();
    const kept = page.keep(s.promise);
    unmount();
    s.resolve();

    expect(await kept).toBeNull();
    expect(s.off).toHaveBeenCalledTimes(1);
    expect(page.isAlive()).toBe(false);
  });

  it('does not throw when releasing fails because the connection is gone', async () => {
    const { page, unmount } = mountPage();
    unmount();
    const off = vi.fn(async () => {
      throw new Error('socket closed');
    });

    await expect(page.keep(Promise.resolve(off))).resolves.toBeNull();
  });
});
