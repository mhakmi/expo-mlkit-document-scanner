import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';

import { importFiles, isAvailable, PdfError, pickFiles, prepare, scan, ScanError, writePdf } from './index';

type Listener = (event: { fraction: number }) => void;

const mock = vi.hoisted(() => {
  const listeners = new Set<Listener>();
  const fake = {
    listeners,
    isAvailable: vi.fn(),
    prepare: vi.fn(),
    scan: vi.fn(),
    pickFiles: vi.fn(),
    importFiles: vi.fn(),
    writePdf: vi.fn(),
    addListener(_name: string, listener: Listener) {
      listeners.add(listener);
      return { remove: () => listeners.delete(listener) };
    },
  };
  return { current: fake as typeof fake | null, fake };
});

vi.mock('./native', () => ({
  get native() {
    return mock.current;
  },
}));

const fake = mock.fake;

function nativeError(code: string, message = `native ${code}`) {
  return Object.assign(new Error(message), { code });
}

const page = { uri: 'file:///pages/a.jpg', width: 1240, height: 1754 };

beforeEach(() => {
  vi.resetAllMocks();
  fake.listeners.clear();
  mock.current = fake;
});

describe('scan', () => {
  it('fills the defaults the native record expects', async () => {
    fake.scan.mockResolvedValue([page]);
    await scan({ directory: 'file:///pages' });
    expect(fake.scan).toHaveBeenCalledWith({ directory: 'file:///pages', pageLimit: 0, galleryImport: false, mode: 'full' });
  });

  it('passes the options it is given', async () => {
    fake.scan.mockResolvedValue([page]);
    await scan({ directory: 'file:///pages', pageLimit: 3, galleryImport: true, mode: 'filter' });
    expect(fake.scan).toHaveBeenCalledWith({ directory: 'file:///pages', pageLimit: 3, galleryImport: true, mode: 'filter' });
  });

  it('returns the pages it scanned', async () => {
    fake.scan.mockResolvedValue([page, { ...page, uri: 'file:///pages/b.jpg' }]);
    await expect(scan({ directory: 'file:///pages' })).resolves.toEqual({
      kind: 'scanned',
      pages: [page, { ...page, uri: 'file:///pages/b.jpg' }],
    });
  });

  it('turns an empty page list, which is what a closed scanner sends, into a cancelled result', async () => {
    fake.scan.mockResolvedValue([]);
    await expect(scan({ directory: 'file:///pages' })).resolves.toEqual({ kind: 'cancelled' });
  });

  it.each([
    ['ERR_SCANNER_BUSY', 'busy'],
    ['ERR_SCANNER_UNAVAILABLE', 'unavailable'],
    ['ERR_SCAN_FAILED', 'failed'],
    ['ERR_SOMETHING_NEW', 'failed'],
  ])('throws %s as a ScanError of kind %s with the message kept', async (code, kind) => {
    fake.scan.mockRejectedValue(nativeError(code, 'what went wrong'));
    const error = await scan({ directory: 'file:///pages' }).catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(ScanError);
    expect(error).toMatchObject({ kind, message: 'what went wrong' });
  });
});

describe('prepare', () => {
  it('reports progress and stops listening once it resolves', async () => {
    const fractions: number[] = [];
    fake.prepare.mockImplementation(async () => {
      fake.listeners.forEach((listener) => listener({ fraction: 0.5 }));
    });
    await prepare({ onProgress: (fraction) => fractions.push(fraction) });
    expect(fractions).toEqual([0.5]);
    expect(fake.listeners.size).toBe(0);
  });

  it('stops listening once it rejects, and throws a ScanError', async () => {
    fake.prepare.mockRejectedValue(nativeError('ERR_SCANNER_UNAVAILABLE'));
    const error = await prepare({ onProgress: () => {} }).catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(ScanError);
    expect(error).toMatchObject({ kind: 'unavailable' });
    expect(fake.listeners.size).toBe(0);
  });

  it('gives the download 60 seconds without progress by default', async () => {
    fake.prepare.mockResolvedValue(undefined);
    await prepare();
    expect(fake.prepare).toHaveBeenCalledWith(60000);
  });

  it('passes the stall timeout it is given', async () => {
    fake.prepare.mockResolvedValue(undefined);
    await prepare({ stallTimeoutMs: 20000 });
    expect(fake.prepare).toHaveBeenCalledWith(20000);
  });

  it('throws a stalled download as a ScanError of kind timeout and stops listening', async () => {
    fake.prepare.mockRejectedValue(nativeError('ERR_PREPARE_TIMEOUT', 'The scanner download made no progress for 20 seconds'));
    const error = await prepare({ onProgress: () => {}, stallTimeoutMs: 20000 }).catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(ScanError);
    expect(error).toMatchObject({ kind: 'timeout', message: 'The scanner download made no progress for 20 seconds' });
    expect(fake.listeners.size).toBe(0);
  });

  it.each([0, -1, Number.NaN, Number.POSITIVE_INFINITY])(
    'throws a RangeError for a stall timeout of %s without starting the download',
    async (stallTimeoutMs) => {
      const error = await prepare({ onProgress: () => {}, stallTimeoutMs }).catch((thrown: unknown) => thrown);
      expect(error).toBeInstanceOf(RangeError);
      expect(fake.prepare).not.toHaveBeenCalled();
      expect(fake.listeners.size).toBe(0);
    },
  );
});

describe('writePdf', () => {
  it('returns the summary', async () => {
    fake.writePdf.mockResolvedValue({ pageCount: 1, bytes: 2048 });
    await expect(writePdf([{ uri: page.uri }], 'file:///out.pdf')).resolves.toEqual({ pageCount: 1, bytes: 2048 });
  });

  it.each([
    ['ERR_PAGE_MISSING', 'page_missing'],
    ['ERR_OUT_OF_SPACE', 'out_of_space'],
    ['ERR_DECODE_FAILED', 'decode_failed'],
    ['ERR_UNEXPECTED', 'failed'],
  ])('throws %s as a PdfError of kind %s', async (code, kind) => {
    fake.writePdf.mockRejectedValue(nativeError(code));
    const error = await writePdf([{ uri: page.uri }], 'file:///out.pdf').catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(PdfError);
    expect(error).toMatchObject({ kind });
  });
});

describe('files', () => {
  it('throws a busy picker as a ScanError', async () => {
    fake.pickFiles.mockRejectedValue(nativeError('ERR_SCANNER_BUSY'));
    await expect(pickFiles('file:///pages')).rejects.toMatchObject({ name: 'ScanError', kind: 'busy' });
  });

  it('imports the URIs it is given', async () => {
    const result = { files: [], skipped: 1 };
    fake.importFiles.mockResolvedValue(result);
    await expect(importFiles(['content://a'], 'file:///pages')).resolves.toBe(result);
    expect(fake.importFiles).toHaveBeenCalledWith(['content://a'], 'file:///pages');
  });
});

describe('on a platform without the native module', () => {
  beforeEach(() => {
    mock.current = null;
  });

  it('is not available', async () => {
    await expect(isAvailable()).resolves.toBe(false);
  });

  it('throws ScanError unavailable from the scanner functions', async () => {
    for (const call of [() => scan({ directory: 'file:///pages' }), () => prepare(), () => pickFiles('file:///pages')]) {
      const error = await call().catch((thrown: unknown) => thrown);
      expect(error).toBeInstanceOf(ScanError);
      expect(error).toMatchObject({ kind: 'unavailable' });
    }
  });

  it('throws PdfError failed from writePdf', async () => {
    const error = await writePdf([{ uri: page.uri }], 'file:///out.pdf').catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(PdfError);
    expect(error).toMatchObject({ kind: 'failed' });
  });
});

it('needs at least one page to write a PDF', () => {
  expectTypeOf<[]>().not.toExtend<Parameters<typeof writePdf>[0]>();
});
