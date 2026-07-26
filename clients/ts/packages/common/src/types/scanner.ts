/**
 * Shared types for the `scanner` HAL interface. A scan is triggered on a backend and the decoded
 * barcode is delivered asynchronously via the `scanner.onScan` event.
 */

/** A decoded barcode delivered by the `scanner` interface's `onScan` event. */
export interface ScanResult {
  /** Decoded payload (text). */
  data: string;
  /** Symbology / format, e.g. "EAN13" or "QR_CODE". */
  format: string;
  /** Raw bytes, base64-encoded, when the backend provides them. */
  rawData?: string;
}
