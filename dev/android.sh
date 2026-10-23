#!/bin/bash
#   dev/android.sh build         build the package, generate example/android, and build the example's release APK for arm64-v8a
#   dev/android.sh badging       print the APK's package, versions, SDK levels, and permissions
#   dev/android.sh unit          run the Vitest tests and the Kotlin JVM tests
#   dev/android.sh gradle <args> run Gradle in example/android, for example gradle :expo-mlkit-document-scanner:assembleDebugAndroidTest
#   dev/android.sh boot          boot the scanner_pixel emulator headless, creating it if missing
#   dev/android.sh install       install the APK on the emulator
#   dev/android.sh scan          scan three pages and two gallery pictures in the example app, cancel a scan, write a PDF, and pick a PDF
#   dev/android.sh check         run the instrumented tests on the emulator, then open their 10-page PDF on the Mac
#   dev/android.sh shot <name>   save a screenshot as .dev-data/shots/<name>.png
#   dev/android.sh adb <args>    run adb against this package's own adb server
#   dev/android.sh fn <name> ... run one of this script's functions by hand, for example fn tap "Scan"
#   dev/android.sh stop          stop this emulator and its adb server, and nothing else
#
# Other projects run adb servers on ports 5037 and 5038 with their own keys. This emulator
# registers only with an adb server of its own on ADB_PORT, and its console ports sit above
# 5585, the last port adb servers scan for emulators, so the other servers never see it.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
EXAMPLE=$ROOT/example
DATA=$ROOT/.dev-data
if [ -f "$DATA/toolchain.env" ]; then
  source "$DATA/toolchain.env"
fi
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17, here or in .dev-data/toolchain.env}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK, here or in .dev-data/toolchain.env}"

export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=$DATA/gradle
export __UNSAFE_EXPO_HOME_DIRECTORY=$DATA/expo-home
export ANDROID_AVD_HOME=$ROOT/.avd
export ANDROID_USER_HOME=$DATA/android-user
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

APK=$EXAMPLE/android/app/build/outputs/apk/release/app-release.apk
APP_ID=$(sed -nE 's/.*"package": "([^"]+)".*/\1/p' "$EXAMPLE/app.json")
AVD=scanner_pixel
ADB_PORT=5039
CONSOLE_PORT=5620
ADB_DEVICE_PORT=5621
GRPC_PORT=8574
export ANDROID_ADB_SERVER_PORT=$ADB_PORT
SERIAL=emulator-$CONSOLE_PORT
BUILD_TOOLS=$ANDROID_HOME/build-tools/36.0.0
LOGS=$DATA/logs
SHOTS=$DATA/shots
FIXTURES=$ROOT/dev/fixtures
POSTER=$FIXTURES/lease-notice.png
GRPCURL=${GRPCURL:-grpcurl}

mkdir -p "$DATA/home" "$DATA/tmp" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$LOGS" "$SHOTS"
# adb and the emulator read and write their keys under $HOME/.android, whatever ANDROID_USER_HOME says.
[ -e "$DATA/home/.android" ] || ln -s ../android-user "$DATA/home/.android"

adb_server() {
  HOME=$DATA/home TMPDIR=$DATA/tmp command adb -P "$ADB_PORT" "$@"
}

adb() {
  adb_server -s "$SERIAL" "$@"
}

emulator() {
  HOME=$DATA/home TMPDIR=$DATA/tmp command emulator "$@"
}

gradle() {
  local log=$LOGS/gradle-$1.log
  log=${log//:/-}
  (cd "$EXAMPLE/android" && ./gradlew "$@" -Pandroid.builder.sdkDownload=false --console=plain) > "$log" 2>&1 || {
    echo "Gradle failed, see $log" >&2
    grep -A 6 'What went wrong' "$log" >&2
    return 1
  }
  grep -E '^BUILD ' "$log" || true
}

prebuild() {
  cd "$ROOT"
  bun install > "$LOGS/bun-install.log" 2>&1
  bun run build > "$LOGS/package-build.log" 2>&1
  cd "$EXAMPLE"
  bun install > "$LOGS/bun-install-example.log" 2>&1
  CI=1 bunx expo prebuild --platform android --clean --no-install > "$LOGS/prebuild.log" 2>&1
  # The scan run copies the pages and the PDF out of the app's private folder with run-as, which needs a debuggable build.
  perl -0pi -e 's/(\n        release \{\n)/$1            debuggable true\n/' "$EXAMPLE/android/app/build.gradle"
  grep -q 'debuggable true' "$EXAMPLE/android/app/build.gradle"
}

build() {
  prebuild
  gradle assembleRelease -PreactNativeArchitectures=arm64-v8a
  echo "$APK"
  badging
}

badging() {
  "$BUILD_TOOLS/aapt2" dump badging "$APK" \
    | grep -E "^(package|sdkVersion|minSdkVersion|targetSdkVersion|uses-permission|application-label):"
}

unit() {
  cd "$ROOT"
  bun run test
  if [ ! -d "$EXAMPLE/android" ]; then
    prebuild
  fi
  gradle :expo-mlkit-document-scanner:testDebugUnitTest
  local results=$ROOT/android/build/test-results/testDebugUnitTest
  grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$results"/*.xml
}

create_avd() {
  local folder=$ANDROID_AVD_HOME/$AVD.avd
  mkdir -p "$folder"
  cat > "$ANDROID_AVD_HOME/$AVD.ini" <<INI
avd.ini.encoding=UTF-8
path=$folder
target=android-35
INI
  cat > "$folder/config.ini" <<INI
avd.ini.encoding=UTF-8
AvdId=$AVD
avd.ini.displayname=$AVD
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
image.sysdir.1=system-images/android-35/google_apis_playstore/arm64-v8a/
tag.id=google_apis_playstore
PlayStore.enabled=yes
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.ramSize=3072
disk.dataPartition.size=6G
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.camera.back=virtualscene
hw.camera.front=none
hw.audioInput=no
INI
}

is_booted() {
  [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]
}

boot() {
  if is_booted; then
    echo "$SERIAL is already running"
    return 0
  fi
  if [ ! -f "$ANDROID_AVD_HOME/$AVD.avd/config.ini" ]; then
    create_avd
  fi

  [ -f "$POSTER" ] || "$FIXTURES/make-fixtures.sh" > /dev/null
  # The Play system image trusts only the adb key it finds on first boot. Without one, adb shows the emulator as unauthorized.
  [ -f "$ANDROID_USER_HOME/adbkey" ] || adb_server keygen "$ANDROID_USER_HOME/adbkey" > /dev/null
  adb_server start-server
  emulator -avd "$AVD" -ports "$CONSOLE_PORT,$ADB_DEVICE_PORT" -grpc "$GRPC_PORT" \
    -camera-back virtualscene -virtualscene-poster "wall=$POSTER" \
    -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu host > "$LOGS/emulator.log" 2>&1 &
  disown

  local attempt
  for attempt in $(seq 1 100); do
    if is_booted; then
      break
    fi
    sleep 3
  done
  is_booted || {
    echo "$SERIAL did not finish booting, see $LOGS/emulator.log" >&2
    adb_server devices | grep "^$SERIAL" >&2 || true
    return 1
  }
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 0
  adb shell settings put secure immersive_mode_confirmations confirmed
  echo "$SERIAL booted"
}

install() {
  adb install -r "$APK"
}

check() {
  gradle :expo-mlkit-document-scanner:assembleDebugAndroidTest
  adb install -r "$ROOT/android/build/outputs/apk/androidTest/debug/expo-mlkit-document-scanner-debug-androidTest.apk" > /dev/null
  adb logcat -c
  local result
  result=$(adb shell am instrument -w dev.mhakmi.documentscanner.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r')
  echo "$result"
  adb logcat -d -s PdfWriterTest:I | grep PdfWriterTest || true
  adb exec-out run-as dev.mhakmi.documentscanner.test cat files/pdf-check/ten-pages.pdf > "$DATA/ten-pages.pdf"
  pdf_pages "$DATA/ten-pages.pdf"
  echo "$result" | grep -q '^OK '
}

pdf_pages() {
  swift "$ROOT/dev/pdf-pages.swift" "$1"
}

node_bounds() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml | tr '>' '\n' \
    | sed -nE 's/.* text="([^"]*)".* class="([^"]*)".* content-desc="([^"]*)".* bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1|\2|\3|\4 \5 \6 \7/p' \
    | awk -F'|' -v label="$1" '$1 == label || $3 == label { print $4; exit }'
}

wait_for() {
  local attempt
  for attempt in $(seq 1 "$2"); do
    if [ -n "$(node_bounds "$1")" ]; then
      return 0
    fi
    sleep 1
  done
  echo "gave up waiting for \"$1\"" >&2
  return 1
}

tap() {
  local bounds
  bounds=$(node_bounds "$1")
  if [ -z "$bounds" ]; then
    echo "no node labelled \"$1\"" >&2
    return 1
  fi
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

screen_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | sed -nE 's/.* text="([^"]+)".*/\1/p'
}

set_physical_model() {
  "$GRPCURL" -plaintext -import-path "$ANDROID_HOME/emulator/lib" -proto emulator_controller.proto \
    -d "{\"target\":\"$1\",\"value\":{\"data\":[$2,$3,$4]}}" \
    "localhost:$GRPC_PORT" android.emulation.control.EmulatorController/setPhysicalModel > /dev/null
}

POSTER_VIEW_POSITION="-1.6 0.3 3.4"
POSTER_VIEW_ROTATION="0 210 0"
BLANK_WALL_POSITION="0 0 0"
BLANK_WALL_ROTATION="0 135 0"

face_poster() {
  set_physical_model POSITION $POSTER_VIEW_POSITION
  set_physical_model ROTATION $POSTER_VIEW_ROTATION
}

face_blank_wall() {
  set_physical_model POSITION $BLANK_WALL_POSITION
  set_physical_model ROTATION $BLANK_WALL_ROTATION
}

launch_clean() {
  adb shell pm clear "$APP_ID" > /dev/null
  adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  wait_for "Check scanner" 30
}

# Play services downloads the scanner over QUIC, which the emulator's network sometimes breaks.
# Play services gives up on that try, and the next prepare starts a new download.
prepare_scanner() {
  local attempt
  tap "Check scanner"
  wait_for "Available: yes" 10
  for attempt in 1 2 3 4 5; do
    tap "Prepare scanner"
    if wait_for "Prepare: done" 90; then
      return 0
    fi
  done
  return 1
}

put_pictures_in_gallery() {
  local name
  for name in receipt.jpg lease-notice.jpg; do
    adb push "$FIXTURES/$name" "/sdcard/Pictures/$name" > /dev/null
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Pictures/$name" > /dev/null
  done
}

# Google's scanner opens in auto capture and takes a page as soon as it sees one,
# so it opens on a blank wall and takes each page by hand. Its labels have changed
# between Play services releases, so a button is tried under both names.
add_page() {
  face_blank_wall
  tap "Add page." || tap "Add page. Button."
  wait_for "Capture" 30
}

capture_pages() {
  local run=$1 count=$2 page
  wait_for "Capture" 240
  sleep 2
  shot "$run-scanner"
  for page in $(seq 1 "$count"); do
    if [ "$page" -gt 1 ]; then
      add_page
    fi
    tap "Manual" || true
    face_poster
    sleep 3
    tap "Capture"
    wait_for "Page $page of $page. Selected." 30
  done
}

import_from_gallery() {
  local run=$1 total=$2 bounds
  add_page
  tap "Import from gallery"
  wait_for "Photos" 30
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml | tr '>' '\n' \
    | sed -nE 's/.* content-desc="Photo taken on[^"]*".* bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p' \
    | while read -r left top right bottom; do
        adb shell input tap $(( (left + right) / 2 )) $(( (top + bottom) / 2 )) < /dev/null
        sleep 1
      done
  wait_for "Add (2)" 10
  shot "$run-gallery"
  tap "Add (2)"
  wait_for "Page $total of $total. Selected." 60
  sleep 2
  shot "$run-scanner-pages"
}

scroll_down() {
  adb shell input swipe 540 1800 540 600 400
  sleep 1
}

scroll_up() {
  adb shell input swipe 540 600 540 1800 400
  sleep 1
}

pull_app_file() {
  adb exec-out run-as "$APP_ID" cat "files/$1" > "$2"
}

scan() {
  launch_clean
  prepare_scanner
  shot scan-1-prepared
  put_pictures_in_gallery

  face_blank_wall
  tap "Scan"
  capture_pages scan-2 3
  import_from_gallery scan-3 5
  tap "Next" || tap "Done"
  wait_for "Status: Scanned 5 pages" 30
  sleep 1
  shot scan-4-pages
  scroll_down
  shot scan-5-pages-scrolled
  screen_text | grep '^Page [0-9]'
  scroll_up

  face_blank_wall
  tap "Scan"
  wait_for "Capture" 60
  adb shell input keyevent KEYCODE_BACK
  wait_for "Status: Scan cancelled" 20
  shot scan-6-cancelled

  tap "Write PDF"
  wait_for "Status: PDF written" 30
  shot scan-7-pdf-written
  pull_app_file example.pdf "$DATA/example.pdf"
  pdf_pages "$DATA/example.pdf"
  local jpeg_bytes pdf_bytes
  jpeg_bytes=$(adb shell "run-as $APP_ID sh -c 'cat files/pages/*.jpg | wc -c'" | tr -d '\r ')
  pdf_bytes=$(wc -c < "$DATA/example.pdf" | tr -d ' ')
  echo "the PDF holds $jpeg_bytes bytes of JPEG and $(( pdf_bytes - jpeg_bytes )) bytes more"
  [ "$pdf_bytes" -ge "$jpeg_bytes" ] && [ $(( pdf_bytes - jpeg_bytes )) -lt 5000 ]

  adb push "$FIXTURES/lease-notice.pdf" /sdcard/Download/lease-notice.pdf > /dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/lease-notice.pdf > /dev/null
  tap "Pick files"
  wait_for "lease-notice.pdf" 30
  shot scan-8-file-picker
  tap "lease-notice.pdf"
  wait_for "Status: Imported 0 images and 1 PDFs, skipped 0" 30
  scroll_down
  scroll_down
  wait_for "lease-notice.pdf: 1 page, $(wc -c < "$FIXTURES/lease-notice.pdf" | tr -d ' ') bytes" 10
  shot scan-9-picked-pdf
  echo "scan run passed, screenshots in $SHOTS"
}

shot() {
  adb exec-out screencap -p > "$SHOTS/$1.png"
  echo "$SHOTS/$1.png"
}

stop() {
  adb emu kill || true
  adb_server kill-server || true
}

case "${1:-}" in
  build | badging | unit | boot | install | check | scan | stop)
    "$1"
    ;;
  gradle)
    shift
    if [ $# -eq 0 ]; then
      echo "usage: $0 gradle <task> [args]" >&2
      exit 1
    fi
    gradle "$@"
    ;;
  adb)
    shift
    adb_server "$@"
    ;;
  shot)
    shot "${2:?usage: $0 shot <name>}"
    ;;
  fn)
    shift
    "$@"
    ;;
  *)
    sed -n 's/^#   //p' "$0"
    exit 1
    ;;
esac
