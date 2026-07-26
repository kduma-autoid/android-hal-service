import type { PrinterCapabilities, PrinterImageStyle } from '../types/printer.js';

/**
 * Unified control surface for a printer backend behind the server-side `printer` interface.
 *
 * Every operation is optional: a method is present only when the bound backend advertises the
 * matching feature in {@link PrinterCapabilities}. This mirrors the server's method-level feature
 * gate (e.g. a printer without ZPL support has no `printZpl`), so callers branch on the method's
 * presence rather than on diverging types:
 *
 * ```ts
 * if (printer.printZpl) await printer.printZpl(zplBase64);
 * ```
 */
export interface IPrinter {
  /** Static description of the command formats this backend supports. */
  readonly capabilities: PrinterCapabilities;

  /** Sends raw ESC/POS command bytes (base64). Present only when `capabilities.escpos`. */
  printEscPos?(data: string): Promise<void>;

  /** Sends raw TSPL command bytes (base64). Present only when `capabilities.tspl`. */
  printTspl?(data: string): Promise<void>;

  /** Sends raw ZPL command bytes (base64). Present only when `capabilities.zpl`. */
  printZpl?(data: string): Promise<void>;

  /** Prints a bitmap (base64 PNG/JPEG) with optional style. Present only when `capabilities.image`. */
  printImage?(bitmap: string, style?: PrinterImageStyle): Promise<void>;

  /** Cuts the paper. Present only when `capabilities.cut`. */
  cut?(): Promise<void>;
}
