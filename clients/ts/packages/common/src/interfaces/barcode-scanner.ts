import type { ScanResult } from '../types/barcode-scanner.js';

/**
 * Unified control surface for a barcode-scanner backend behind the server-side `barcodeScanner`
 * interface.
 *
 * Trigger a scan; decoded barcodes arrive asynchronously via {@link IBarcodeScanner.onScan}. A
 * concrete client is bound to a single backend, so `onScan` delivers only that scanner's results
 * (filtered by event source).
 */
export interface IBarcodeScanner {
  /** Starts a scan on the bound backend. Results are delivered via {@link onScan}. */
  trigger(): Promise<void>;

  /** Stops scanning on the bound backend. */
  stop(): Promise<void>;

  /**
   * Subscribes to decoded barcodes from the bound backend only. Resolves to an unsubscribe
   * function.
   */
  onScan(handler: (scan: ScanResult) => void): Promise<() => Promise<void>>;
}
