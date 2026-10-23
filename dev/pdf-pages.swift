import Foundation
import PDFKit

let path = CommandLine.arguments[1]
guard let document = PDFDocument(url: URL(fileURLWithPath: path)) else {
  print("\(path) does not open as a PDF")
  exit(1)
}
let bytes = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? Int) ?? 0
print("\(path): \(bytes) bytes, \(document.pageCount) pages")
for index in 0..<document.pageCount {
  let box = document.page(at: index)!.bounds(for: .mediaBox)
  print("  page \(index + 1): \(Int(box.width)) x \(Int(box.height)) pt")
}
