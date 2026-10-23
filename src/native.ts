import { type NativeModule, requireOptionalNativeModule } from 'expo';

import type { ImportResult, PdfPage, PdfSummary, ScanMode, ScannedImage } from './index';

type Events = {
  onPrepareProgress: (event: { fraction: number }) => void;
};

type NativeScanOptions = {
  directory: string;
  pageLimit: number;
  galleryImport: boolean;
  mode: ScanMode;
};

declare class DocumentScannerModule extends NativeModule<Events> {
  isAvailable(): Promise<boolean>;
  prepare(): Promise<void>;
  scan(options: NativeScanOptions): Promise<ScannedImage[]>;
  pickFiles(directory: string): Promise<ImportResult>;
  importFiles(uris: string[], directory: string): Promise<ImportResult>;
  writePdf(pages: PdfPage[], outputUri: string): Promise<PdfSummary>;
}

export const native = requireOptionalNativeModule<DocumentScannerModule>('MlkitDocumentScanner');
