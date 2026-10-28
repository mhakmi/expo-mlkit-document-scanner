import type { PdfErrorKind, ScanErrorKind } from './index';

export const scanErrorKinds: Record<string, ScanErrorKind> = {
  ERR_SCANNER_UNAVAILABLE: 'unavailable',
  ERR_SCANNER_BUSY: 'busy',
  ERR_PREPARE_TIMEOUT: 'timeout',
  ERR_SCAN_FAILED: 'failed',
};

export const pdfErrorKinds: Record<string, PdfErrorKind> = {
  ERR_PAGE_MISSING: 'page_missing',
  ERR_DECODE_FAILED: 'decode_failed',
  ERR_OUT_OF_SPACE: 'out_of_space',
};
