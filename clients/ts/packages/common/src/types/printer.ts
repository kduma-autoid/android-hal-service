/**
 * Shared types for the `printer` HAL interface. Backends advertise which command formats they
 * support via {@link PrinterCapabilities} (mirroring the server-side interface features), so a
 * caller programs against one unified surface and the backend stays transparent.
 */

/** Optional command formats / operations a printer backend may advertise. */
export const PRINTER_FEATURES = ['escpos', 'tspl', 'zpl', 'image', 'cut'] as const;

export type PrinterFeature = typeof PRINTER_FEATURES[number];

/** Optional rendering style for {@link IPrinter.printImage}; forwarded opaquely to the backend. */
export interface PrinterImageStyle {
  /** Dithering / threshold algorithm, e.g. "BINARIZATION" or "DITHERING". */
  algorithm?: string;
  /** Algorithm parameter (e.g. a threshold in 0..255). */
  value?: number;
  [key: string]: unknown;
}

/** Which optional command formats the bound printer backend supports. */
export interface PrinterCapabilities {
  /** Raw ESC/POS command bytes (`printEscPos`). */
  escpos: boolean;
  /** Raw TSPL command bytes (`printTspl`). */
  tspl: boolean;
  /** Raw ZPL command bytes (`printZpl`). */
  zpl: boolean;
  /** Bitmap / image printing (`printImage`). */
  image: boolean;
  /** Paper cut (`cut`). */
  cut: boolean;
}
