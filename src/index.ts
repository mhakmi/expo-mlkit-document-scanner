import { pdfErrorKinds, scanErrorKinds } from './codes';
import { native } from './native';

export type Rotation = 0 | 90 | 180 | 270;

export type ScanMode = 'full' | 'base' | 'filter';

export type ScanOptions = {
  /** A file:// directory. Created if missing. Pages are copied here under UUID names. */
  directory: string;
  /** 0 or absent means no limit. */
  pageLimit?: number;
  galleryImport?: boolean;
  mode?: ScanMode;
};

/** An upright JPEG. width and height already account for EXIF orientation. */
export type ScannedImage = { uri: string; width: number; height: number };

export type ScanResult = { kind: 'scanned'; pages: [ScannedImage, ...ScannedImage[]] } | { kind: 'cancelled' };

export type ImportedFile =
  | { kind: 'image'; uri: string; name: string; width: number; height: number }
  | { kind: 'pdf'; uri: string; name: string; pageCount: number; thumbnailUri: string };

/** skipped counts inputs that were not an image or a PDF, or could not be read. */
export type ImportResult = { files: ImportedFile[]; skipped: number };

export type PdfPage = { uri: string; rotation?: Rotation };

export type PdfSummary = { pageCount: number; bytes: number };

export type ScanErrorKind = 'unavailable' | 'busy' | 'failed';

export type PdfErrorKind = 'page_missing' | 'decode_failed' | 'out_of_space' | 'failed';

export class ScanError extends Error {
  constructor(
    readonly kind: ScanErrorKind,
    message: string,
  ) {
    super(message);
    this.name = 'ScanError';
  }
}

export class PdfError extends Error {
  constructor(
    readonly kind: PdfErrorKind,
    message: string,
  ) {
    super(message);
    this.name = 'PdfError';
  }
}

const androidOnly = 'expo-mlkit-document-scanner runs on Android only';

function codeOf(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string') {
    return error.code;
  }
  return '';
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function toScanError(error: unknown): ScanError {
  return new ScanError(scanErrorKinds[codeOf(error)] ?? 'failed', messageOf(error));
}

function toPdfError(error: unknown): PdfError {
  return new PdfError(pdfErrorKinds[codeOf(error)] ?? 'failed', messageOf(error));
}

function scanner() {
  if (native === null) {
    throw new ScanError('unavailable', androidOnly);
  }
  return native;
}

export async function isAvailable(): Promise<boolean> {
  if (native === null) {
    return false;
  }
  return native.isAvailable();
}

export async function prepare(onProgress?: (fraction: number) => void): Promise<void> {
  const module = scanner();
  const subscription =
    onProgress === undefined ? null : module.addListener('onPrepareProgress', (event) => onProgress(event.fraction));
  try {
    await module.prepare();
  } catch (error) {
    throw toScanError(error);
  } finally {
    subscription?.remove();
  }
}

export async function scan(options: ScanOptions): Promise<ScanResult> {
  const module = scanner();
  let pages: ScannedImage[];
  try {
    pages = await module.scan({
      directory: options.directory,
      pageLimit: options.pageLimit ?? 0,
      galleryImport: options.galleryImport ?? false,
      mode: options.mode ?? 'full',
    });
  } catch (error) {
    throw toScanError(error);
  }
  const [first, ...rest] = pages;
  if (first === undefined) {
    return { kind: 'cancelled' };
  }
  return { kind: 'scanned', pages: [first, ...rest] };
}

export async function pickFiles(directory: string): Promise<ImportResult> {
  const module = scanner();
  try {
    return await module.pickFiles(directory);
  } catch (error) {
    throw toScanError(error);
  }
}

export async function importFiles(uris: string[], directory: string): Promise<ImportResult> {
  const module = scanner();
  try {
    return await module.importFiles(uris, directory);
  } catch (error) {
    throw toScanError(error);
  }
}

export async function writePdf(pages: [PdfPage, ...PdfPage[]], outputUri: string): Promise<PdfSummary> {
  if (native === null) {
    throw new PdfError('failed', androidOnly);
  }
  try {
    return await native.writePdf(pages, outputUri);
  } catch (error) {
    throw toPdfError(error);
  }
}
