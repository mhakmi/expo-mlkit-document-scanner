import { Directory, File, Paths } from 'expo-file-system';
import {
  importFiles,
  isAvailable,
  pickFiles,
  prepare,
  scan,
  writePdf,
  type ImportedFile,
  type PdfPage,
  type ScanMode,
  type ScannedImage,
} from 'expo-mlkit-document-scanner';
import { useState } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';

const modes: ScanMode[] = ['full', 'base', 'filter'];

type PdfFile = Extract<ImportedFile, { kind: 'pdf' }>;

function pageCount(count: number): string {
  return count === 1 ? '1 page' : `${count} pages`;
}

function pagesFolder(): Directory {
  const folder = new Directory(Paths.document, 'pages');
  folder.create({ intermediates: true, idempotent: true });
  return folder;
}

function describeError(error: unknown): string {
  if (error instanceof Error) {
    const kind = 'kind' in error ? ` (${String(error.kind)})` : '';
    return `${error.name}${kind}: ${error.message}`;
  }
  return String(error);
}

function fileSize(uri: string): number {
  return new File(uri).size ?? 0;
}

export default function App() {
  const [status, setStatus] = useState('Ready');
  const [available, setAvailable] = useState('not checked');
  const [preparing, setPreparing] = useState('not started');
  const [stallTimeout, setStallTimeout] = useState('');
  const [pageLimit, setPageLimit] = useState('0');
  const [mode, setMode] = useState<ScanMode>('full');
  const [galleryImport, setGalleryImport] = useState(true);
  const [pages, setPages] = useState<ScannedImage[]>([]);
  const [pdfFiles, setPdfFiles] = useState<PdfFile[]>([]);
  const [pdfSummary, setPdfSummary] = useState('none written');
  const [pdfPath, setPdfPath] = useState('');

  async function run(action: () => Promise<void>) {
    try {
      await action();
    } catch (error) {
      setStatus(describeError(error));
    }
  }

  function addImported(files: ImportedFile[], skipped: number) {
    const images = files.filter((file) => file.kind === 'image');
    const pdfs = files.filter((file) => file.kind === 'pdf');
    setPages((current) => [...current, ...images.map(({ uri, width, height }) => ({ uri, width, height }))]);
    setPdfFiles((current) => [...current, ...pdfs]);
    setStatus(`Imported ${images.length} images and ${pdfs.length} PDFs, skipped ${skipped}`);
  }

  const onCheck = () =>
    run(async () => {
      setAvailable((await isAvailable()) ? 'yes' : 'no');
    });

  const onPrepare = () =>
    run(async () => {
      setPreparing('0%');
      const seconds = stallTimeout.trim();
      await prepare({
        onProgress: (fraction) => setPreparing(`${Math.round(fraction * 100)}%`),
        stallTimeoutMs: seconds === '' ? undefined : Number(seconds) * 1000,
      });
      setPreparing('done');
    });

  const onScan = () =>
    run(async () => {
      const result = await scan({
        directory: pagesFolder().uri,
        pageLimit: Number.parseInt(pageLimit, 10) || 0,
        galleryImport,
        mode,
      });
      if (result.kind === 'cancelled') {
        setStatus('Scan cancelled');
        return;
      }
      setPages((current) => [...current, ...result.pages]);
      setStatus(`Scanned ${pageCount(result.pages.length)}`);
    });

  const onPick = () =>
    run(async () => {
      const result = await pickFiles(pagesFolder().uri);
      addImported(result.files, result.skipped);
    });

  const onImportFirstPage = () =>
    run(async () => {
      const first = pages[0];
      if (first === undefined) {
        setStatus('No page to import');
        return;
      }
      const result = await importFiles([first.uri], pagesFolder().uri);
      addImported(result.files, result.skipped);
    });

  const onWritePdf = () =>
    run(async () => {
      const [first, ...rest] = pages.map((page): PdfPage => ({ uri: page.uri }));
      if (first === undefined) {
        setStatus('No pages to write');
        return;
      }
      const output = new File(Paths.document, 'example.pdf');
      const summary = await writePdf([first, ...rest], output.uri);
      setPdfSummary(`${pageCount(summary.pageCount)}, ${summary.bytes} bytes`);
      setPdfPath(output.uri);
      setStatus('PDF written');
    });

  const onClear = () =>
    run(async () => {
      const folder = new Directory(Paths.document, 'pages');
      if (folder.exists) {
        folder.delete();
      }
      setPages([]);
      setPdfFiles([]);
      setStatus('Cleared');
    });

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Text style={styles.title}>expo-mlkit-document-scanner</Text>
      <Text>Status: {status}</Text>

      <View style={styles.row}>
        <Button label="Check scanner" onPress={onCheck} />
        <Text>Available: {available}</Text>
      </View>
      <View style={styles.row}>
        <Button label="Prepare scanner" onPress={onPrepare} />
        <Text>Prepare: {preparing}</Text>
      </View>
      <View style={styles.row}>
        <Text>Stall timeout (s)</Text>
        <TextInput
          accessibilityLabel="Stall timeout (s)"
          style={styles.input}
          keyboardType="number-pad"
          placeholder="60"
          value={stallTimeout}
          onChangeText={setStallTimeout}
        />
      </View>

      <View style={styles.row}>
        <Text>Page limit</Text>
        <TextInput
          accessibilityLabel="Page limit"
          style={styles.input}
          keyboardType="number-pad"
          value={pageLimit}
          onChangeText={setPageLimit}
        />
      </View>
      <View style={styles.row}>
        {modes.map((option) => (
          <Button
            key={option}
            label={`Mode ${option}`}
            selected={mode === option}
            onPress={() => setMode(option)}
          />
        ))}
      </View>
      <View style={styles.row}>
        <Text>Gallery import</Text>
        <Switch accessibilityLabel="Gallery import" value={galleryImport} onValueChange={setGalleryImport} />
      </View>

      <View style={styles.row}>
        <Button label="Scan" onPress={onScan} />
        <Button label="Pick files" onPress={onPick} />
        <Button label="Import first page" onPress={onImportFirstPage} />
      </View>
      <View style={styles.row}>
        <Button label="Write PDF" onPress={onWritePdf} />
        <Button label="Clear" onPress={onClear} />
      </View>
      <Text>PDF: {pdfSummary}</Text>
      {pdfPath === '' ? null : <Text selectable>PDF path: {pdfPath}</Text>}

      <Text style={styles.heading}>Pages: {pages.length}</Text>
      {pages.map((page, index) => (
        <View key={page.uri} style={styles.item}>
          <Image source={{ uri: page.uri }} style={[styles.thumbnail, { aspectRatio: page.width / page.height }]} />
          <Text>
            Page {index + 1}: {page.width} x {page.height}, {fileSize(page.uri)} bytes
          </Text>
        </View>
      ))}

      <Text style={styles.heading}>PDF files: {pdfFiles.length}</Text>
      {pdfFiles.map((file) => (
        <View key={file.uri} style={styles.item}>
          <Image source={{ uri: file.thumbnailUri }} style={[styles.thumbnail, { aspectRatio: 1 / 1.414 }]} />
          <Text>
            {file.name}: {pageCount(file.pageCount)}, {fileSize(file.uri)} bytes
          </Text>
        </View>
      ))}
    </ScrollView>
  );
}

function Button({ label, onPress, selected = false }: { label: string; onPress: () => void; selected?: boolean }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected }}
      onPress={onPress}
      style={[styles.button, selected && styles.selected]}
    >
      <Text style={selected ? styles.selectedText : undefined}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { paddingBlock: 48, paddingInline: 16, gap: 12 },
  title: { fontSize: 20, fontWeight: '600' },
  heading: { fontSize: 16, fontWeight: '600', marginBlockStart: 8 },
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 },
  input: { borderWidth: 1, borderColor: '#888', borderRadius: 4, minWidth: 64, paddingInline: 8, paddingBlock: 4 },
  button: { borderWidth: 1, borderColor: '#333', borderRadius: 6, paddingInline: 12, paddingBlock: 8 },
  selected: { backgroundColor: '#333' },
  selectedText: { color: '#fff' },
  item: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  thumbnail: { width: 64, borderWidth: 1, borderColor: '#ccc' },
});
