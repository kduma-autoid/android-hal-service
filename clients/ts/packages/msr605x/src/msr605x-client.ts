import type { IHalClient } from '@kduma-autoid/hal-client-common';
import type {
  Msr605xBpcSettings,
  Msr605xBpiSettings,
  Msr605xCoercivity,
  Msr605xInfo,
  Msr605xLeadingZeros,
  Msr605xLed,
  Msr605xRawReadResult,
  Msr605xRawWriteTracks,
  Msr605xReadResult,
  Msr605xSwipeOptions,
  Msr605xTrack,
  Msr605xWriteTracks,
} from './types.js';
import { MSR605X_EVENTS } from './types.js';

export const PLUGIN_ID = 'msr605x';

/**
 * Client for the native API of the `msr605x` plugin (MSR605X USB magnetic card reader/writer).
 * For a backend-independent API use the `magneticCardReader` interface facade instead.
 */
export class Msr605xClient {
  private readonly client: IHalClient;

  constructor(client: IHalClient) {
    this.client = client;
  }

  // --- Card ---

  /** Waits for a swipe and reads the ISO 7811 tracks. */
  async read(options?: Msr605xSwipeOptions): Promise<Msr605xReadResult> {
    return this.client.execute<Msr605xReadResult>('msr605x.read', { ...options });
  }

  /** Waits for a swipe and reads raw track data (hex). */
  async readRaw(options?: Msr605xSwipeOptions): Promise<Msr605xRawReadResult> {
    return this.client.execute<Msr605xRawReadResult>('msr605x.readRaw', { ...options });
  }

  /** Waits for a swipe and writes ISO 7811 tracks (data without sentinels). */
  async write(tracks: Msr605xWriteTracks, options?: Msr605xSwipeOptions): Promise<void> {
    await this.client.execute('msr605x.write', { ...tracks, ...options });
  }

  /** Waits for a swipe and writes raw tracks (hex). The device is switched to 8 bits per character first. */
  async writeRaw(tracks: Msr605xRawWriteTracks, options?: Msr605xSwipeOptions): Promise<void> {
    await this.client.execute('msr605x.writeRaw', { ...tracks, ...options });
  }

  /** Waits for a swipe and erases the given tracks. */
  async erase(tracks: Msr605xTrack[], options?: Msr605xSwipeOptions): Promise<void> {
    await this.client.execute('msr605x.erase', { tracks, ...options });
  }

  /** Aborts a pending swipe wait. Resolves to whether anything was pending. */
  async cancel(): Promise<boolean> {
    const res = await this.client.execute<{ cancelled?: boolean }>('msr605x.cancel', {});
    return res?.cancelled === true;
  }

  // --- Settings ---

  async setCoercivity(mode: Msr605xCoercivity): Promise<void> {
    await this.client.execute('msr605x.setCoercivity', { mode });
  }

  /** Current write coercivity, or null when the device did not report it. */
  async getCoercivity(): Promise<Msr605xCoercivity | null> {
    const res = await this.client.execute<{ mode: Msr605xCoercivity | null }>('msr605x.getCoercivity', {});
    return res?.mode ?? null;
  }

  /** Sets bits per inch for the given tracks; resolves to the applied settings. */
  async setBpi(settings: Msr605xBpiSettings): Promise<Msr605xBpiSettings> {
    return this.client.execute<Msr605xBpiSettings>('msr605x.setBpi', { ...settings });
  }

  /** Sets bits per character (5-8) for all tracks; resolves to the applied settings. */
  async setBpc(settings: Msr605xBpcSettings): Promise<Msr605xBpcSettings> {
    return this.client.execute<Msr605xBpcSettings>('msr605x.setBpc', { ...settings });
  }

  async setLeadingZeros(settings: Msr605xLeadingZeros): Promise<void> {
    await this.client.execute('msr605x.setLeadingZeros', { ...settings });
  }

  async getLeadingZeros(): Promise<Msr605xLeadingZeros> {
    return this.client.execute<Msr605xLeadingZeros>('msr605x.getLeadingZeros', {});
  }

  // --- Device ---

  async getInfo(): Promise<Msr605xInfo> {
    return this.client.execute<Msr605xInfo>('msr605x.getInfo', {});
  }

  /**
   * Turns background listening on or off until the service restarts (the default comes from the
   * plugin's "listen automatically" setting). Resolves to the new state.
   */
  async setListening(enabled: boolean): Promise<boolean> {
    const res = await this.client.execute<{ listening?: boolean }>('msr605x.setListening', { enabled });
    return res?.listening === true;
  }

  async setLed(led: Msr605xLed): Promise<void> {
    await this.client.execute('msr605x.setLed', { led });
  }

  async commTest(): Promise<boolean> {
    const res = await this.client.execute<{ ok?: boolean }>('msr605x.commTest', {});
    return res?.ok === true;
  }

  /** Resets the device; a hard reset also aborts a pending operation. */
  async reset(options?: { hard?: boolean }): Promise<void> {
    await this.client.execute('msr605x.reset', { hard: options?.hard ?? false });
  }

  // --- Events ---

  async onSwipe(handler: (data: Msr605xReadResult) => void): Promise<() => Promise<void>> {
    return this.client.on<Msr605xReadResult>(MSR605X_EVENTS.SWIPE, (_, data) => handler(data));
  }

  async onAttached(handler: () => void): Promise<() => Promise<void>> {
    return this.client.on(MSR605X_EVENTS.ATTACHED, () => handler());
  }

  async onDetached(handler: () => void): Promise<() => Promise<void>> {
    return this.client.on(MSR605X_EVENTS.DETACHED, () => handler());
  }
}
