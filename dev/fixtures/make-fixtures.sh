#!/bin/bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
WORK=$ROOT/.dev-data/tmp/fixtures
rm -rf "$WORK"
mkdir -p "$WORK/profile"

cat > "$WORK/lease-notice.html" <<'HTML'
<!doctype html><meta charset="utf-8">
<style>
  body { font: 22px/1.55 Georgia, serif; margin: 90px; color: #1d1d24; }
  h1 { font-size: 40px; margin-block-end: 8px; }
  .meta { color: #555; }
  table { border-collapse: collapse; margin-block: 24px; }
  td { border: 2px solid #333; padding: 10px 18px; }
</style>
<h1>Lease renewal notice</h1>
<p class="meta">Maple Court Apartments, 40 Sample Street, Unit 12. Issued August 1.</p>
<p>Dear tenant,</p>
<p>Your lease for unit 12 renews on September 1 for twelve months. The monthly rent stays the same.
Sign the enclosed form and return it to the office by August 20.</p>
<table>
  <tr><td>Monthly rent</td><td>1850.00</td></tr>
  <tr><td>Parking space</td><td>75.00</td></tr>
  <tr><td>Total each month</td><td>1925.00</td></tr>
</table>
<p>Questions go to the building office, open weekdays from nine to five.</p>
<p>Signed, the property manager</p>
<p class="meta">Sample document made for testing. Not a real record.</p>
HTML

cat > "$WORK/receipt.html" <<'HTML'
<!doctype html><meta charset="utf-8">
<style>
  body { font: 26px/1.5 "Courier New", monospace; margin: 40px; color: #222; }
  h1 { font-size: 34px; text-align: center; }
  .row { display: flex; justify-content: space-between; }
  .meta { text-align: center; color: #555; }
</style>
<h1>Corner Hardware</h1>
<p class="meta">12 Example Road. Receipt 0042.</p>
<div class="row"><span>Wood screws</span><span>6.49</span></div>
<div class="row"><span>Wall anchors</span><span>4.25</span></div>
<div class="row"><span>Paint roller</span><span>9.99</span></div>
<div class="row"><span>Masking tape</span><span>3.79</span></div>
<div class="row"><span>Drop cloth</span><span>12.50</span></div>
<div class="row"><span>Sandpaper</span><span>5.15</span></div>
<hr>
<div class="row"><span>Subtotal</span><span>42.17</span></div>
<div class="row"><span>Tax</span><span>5.48</span></div>
<div class="row"><b>Total</b><b>47.65</b></div>
<p class="meta">Sample receipt made for testing. Not a real record.</p>
HTML

chrome() {
  "$CHROME" --headless --disable-gpu --hide-scrollbars --user-data-dir="$WORK/profile" "$@" > /dev/null 2>&1
}

chrome --window-size=1240,1754 --screenshot="$HERE/lease-notice.png" "file://$WORK/lease-notice.html"
chrome --no-pdf-header-footer --print-to-pdf="$HERE/lease-notice.pdf" "file://$WORK/lease-notice.html"
chrome --window-size=640,1400 --screenshot="$WORK/receipt.png" "file://$WORK/receipt.html"
sips -s format jpeg -s formatOptions 85 "$WORK/receipt.png" --out "$HERE/receipt.jpg" > /dev/null
sips -s format jpeg -s formatOptions 85 "$HERE/lease-notice.png" --out "$HERE/lease-notice.jpg" > /dev/null
rm -rf "$WORK"
ls -l "$HERE"/lease-notice.* "$HERE"/receipt.jpg
