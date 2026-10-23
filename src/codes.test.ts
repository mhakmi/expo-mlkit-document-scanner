import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import { expect, it } from 'vitest';

import { pdfErrorKinds, scanErrorKinds } from './codes';

const kotlinSources = path.join(import.meta.dirname, '..', 'android', 'src', 'main');

function kotlinCodes(): string[] {
  const files = readdirSync(kotlinSources, { recursive: true, encoding: 'utf8' }).filter((name) => name.endsWith('.kt'));
  const codes = files.flatMap((name) => readFileSync(path.join(kotlinSources, name), 'utf8').match(/"ERR_[A-Z_]+"/g) ?? []);
  return [...new Set(codes.map((code) => code.slice(1, -1)))].sort();
}

it('finds the codes the Kotlin module rejects with', () => {
  expect(kotlinCodes()).toContain('ERR_SCANNER_BUSY');
});

it.each(kotlinCodes())('maps %s to an error kind', (code) => {
  expect(code in scanErrorKinds || code in pdfErrorKinds).toBe(true);
});
