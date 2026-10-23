# Prior art for an ML Kit document scanner package

Checked on 2026-10-23. The question was whether an existing React Native or Expo package already does what Waraq's scanner module does. If one did, the plan was to use it and skip this package.

The answer is no. No maintained package covers the list, and none has a PDF writer that keeps memory flat. This package goes ahead.

## What the package has to do

Waraq's module at `waraq/modules/scanner` sets the list:

1. Check that Google's scanner can run on the phone.
2. Download the scanner module ahead of the first scan, with progress.
3. Limit the number of pages.
4. Choose the scanner mode (full, base, or base with filter).
5. Turn gallery import in the scanner on or off.
6. Report typed errors, so an app can tell a cancelled scan from a missing scanner or a busy one.
7. Return JPEG pages, and write a PDF from them.
8. Write that PDF without decoding every page into memory.
9. Pick existing images and PDFs from the phone.

## The packages

npm downloads are for 2026-09-19 to 2026-10-18. "ML Kit PDF" means the package passes `RESULT_FORMAT_PDF` to the scanner and returns the PDF that Google Play services writes. Each row comes from the package's Android source, read on GitHub or from the npm tarball.

| Package | Version, date | Downloads a month | 1 | 2 | 3 | 4 | 5 | 6 | 7, PDF | 9 | iOS | License |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `react-native-document-scanner-plugin` (Daftar uses it) | 2.0.4, 2026-01-02 | 374,026 | no | no | yes | no, always full | no | no, one code for every failure | JPEG only | no | yes | MIT |
| `expo-camera`, `CameraView.scanDocumentAsync` | 58.0.12 (scanner since 58.0.0, 2026-09-10) | whole package | partial | no | no | no, always full | no | yes | ML Kit PDF | no | yes | MIT |
| `@dariyd/react-native-document-scanner` | 2.1.1, 2026-09-14 | 24,693 | no | no | yes | yes | yes | no | ML Kit PDF | no | yes | Apache-2.0 |
| `@infinitered/react-native-mlkit-document-scanner` | 5.0.0, 2025-11-17 | 3,834 | no | no | yes | yes | yes | partial | ML Kit PDF | no | no | MIT |
| `expo-document-scanner` | 0.2.8, 2026-07-01 | 3,676 | no | no | yes | yes | yes | no, callers match "cancel" in the message | ML Kit PDF | no | yes | MIT |
| `@preeternal/react-native-document-scanner-plugin` | 0.4.3, 2026-09-26 | 1,625 | no | no | yes | yes | yes | partial | JPEG only | no | yes | MIT |
| `react-native-receipt-scanner` | 1.0.0, 2026-10-11 | 271 | no | no | yes | no | no | partial | JPEG only | images, with its own crop screen | yes | MIT |

Six more packages each have under 100 downloads a month: `rn-document-scanner-vision`, `@fernandoaoborges/react-native-document-scanner`, `react-native-docs-scanner`, `@nitro-mlkit/document-scanner`, `@atmina/react-native-document-scanner-plugin`, and `@akin67/react-native-document-scanner`. Of all thirteen, only `rn-document-scanner-vision` asks Play services whether the scanner module is installed. It cannot download the module.

Notes on the packages that matter most:

- **`react-native-document-scanner-plugin`.** It has had no commit since 2026-01-02. It has 48 open issues and 4 open pull requests. Five of them ask for the scanner mode and the gallery toggle (#133, #135, #150, #178, #180). Its Kotlin handles only `RESULT_OK` and `RESULT_CANCELED`, so any other result code leaves the promise unsettled. Issue #115 reports a promise that never returns. Daftar's `src/scanner.ts` works around the untyped errors by showing the raw message.
- **`expo-camera`.** Expo added `scanDocumentAsync` in SDK 58, and Expo maintains it. Its errors are typed classes, and a cancelled scan resolves to `null`. It has no page limit, no mode, no gallery toggle, and no early download. Its availability check looks for the Play Store app, not for the scanner module. An app that wants only the scanner still installs CameraX and the camera permission with it.
- **`@infinitered/react-native-mlkit-document-scanner`.** It is Android only and built on the Expo modules API, the closest to this package in shape. npm still serves 5.0.0 for Expo SDK 54, while the repository's changelog lists a 6.0.0 for SDK 56 that was never published. Issue #255 reports `Attempting to launch an unregistered ActivityResultLauncher` from production.

## PDF writers

No scanner package writes its own PDF. The ones that return a PDF hand back the one ML Kit wrote for that scan. That PDF holds the pages of one scan, in the order the scanner returned them. It cannot hold a page that was turned, moved to another document, or imported from the gallery after the scan. Waraq does all three before it uploads.

The image-to-PDF packages fill that gap with Android's `PdfDocument`:

| Package | Version, date | Downloads a month | How it writes |
|---|---|---|---|
| `react-native-images-to-pdf` | 0.2.1, 2023-09-06 | 15,732 | `PdfDocument`, decodes each page to a bitmap and draws it (`ImagesPdfModule.java` lines 8, 66, 94) |
| `react-native-pdf-from-image` | 0.3.6, 2026-01-20 | 8,727 | `PdfDocument`, `BitmapFactory.decodeFile` per page (`PdfFromImageModuleImpl.kt` lines 4, 43, 66) |

Waraq measured that approach on the emulator. Ten pages of 300 dpi A4 came to 99.9 MB, and native memory grew by 348 MB, or 1089 MB for 30 pages. Waraq's writer copies each JPEG into the PDF as it is. The same ten pages came to 25.3 MB with no memory growth. Source: `waraq/README.md`, the table under "How a scan becomes a batch".

Not measured: how large ML Kit's own PDF is for the same scan.

## Decision

Go. Three gains hold up:

- No package downloads the scanner module ahead of time. On a phone that has never scanned, the first scan waits for that download.
- No package writes a PDF from pages the app has changed, without decoding them. The two that write any PDF from images take the approach Waraq measured at four times the size.
- Typed errors exist only in `expo-camera`, which lacks the page limit, the mode, and the gallery toggle.

The package is Android only, like Waraq's module. Both apps target Android first. iOS would need a VisionKit side and is out of scope for now.

## Upstream

The PDF writer does not belong in `expo-camera` or in a scanner plugin. It works on any JPEG, scanned or not. Contributing it upstream makes less sense than publishing it here.

One upstream pull request does make sense. `expo-camera`'s `scanDocumentAsync` could take a page limit, a scanner mode, and a gallery toggle. Each is one call on `GmsDocumentScannerOptions.Builder`, and the scanner plugin's open pull requests show people want them. That contribution is the owner's call.

## Package name

These npm names were free on 2026-10-23 (`npm view <name>` returned 404):

- `expo-mlkit-document-scanner`
- `react-native-mlkit-document-scanner`
- `@mhakmi/document-scanner`
- `@mhakmi/expo-document-scanner`

`expo-document-scanner` is taken.

The `@mhakmi` scope has no published packages. Whether an npm user or organization named `mhakmi` exists could not be checked without logging in to npm. The owner needs an npm account named `mhakmi` before a scoped name can be published.

The working name is `expo-mlkit-document-scanner`. It names the platform and the library, and it needs no scope. Changing it later touches `package.json`, the README, and the example app.
