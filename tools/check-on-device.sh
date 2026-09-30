#!/usr/bin/env bash
#
# Installs the app on a connected phone or emulator and checks it works:
#
#   tools/check-on-device.sh
#
# The unit tests cover everything that can be checked without a screen. This
# covers the rest, which is the part that actually matters: an APK that builds
# perfectly can still crash the moment it opens, and nothing in `./gradlew
# test` would ever notice.
#
# It needs one device visible to adb. Nothing here writes to your own files -
# it pushes a file it made itself into the device's Downloads folder.
set -uo pipefail

ADB="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}/platform-tools/adb"
PKG="io.github.proburakelci.codepad"
ACTIVITY="$PKG/.MainActivity"

passed=0
failed=0

ok() {
  if [ "$2" = "yes" ]; then
    printf '  ok    %s\n' "$1"
    passed=$((passed + 1))
  else
    printf '  FAIL  %s%s\n' "$1" "${3:+   ($3)}"
    failed=$((failed + 1))
  fi
}

if ! "$ADB" shell true >/dev/null 2>&1; then
  echo "No device. Start an emulator or plug a phone in, then try again."
  exit 2
fi

echo
echo "Device: $("$ADB" shell getprop ro.product.model | tr -d '\r') (Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r'))"
echo

# ---- install ----

APK=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
  echo "No APK. Run ./gradlew assembleDebug first."
  exit 2
fi

"$ADB" install -r -g "$APK" >/dev/null 2>&1
ok "the app installs" "$("$ADB" shell pm list packages | grep -qc "$PKG" >/dev/null && echo yes || echo no)"

# ---- the promise in the README ----

# -g above grants every runtime permission the app asks for. If the app asks
# for none, there is nothing to grant, and this is where that gets checked
# against the installed package rather than against the source.
granted=$("$ADB" shell dumpsys package "$PKG" | tr -d '\r' | grep -c 'permission.*granted=true')
ok "the installed app holds no permissions" "$([ "$granted" -eq 0 ] && echo yes || echo no)" "holds $granted"

requested=$("$ADB" shell dumpsys package "$PKG" | tr -d '\r' | sed -n '/requested permissions:/,/^ *[a-z]* permissions:/p' | grep -c 'android.permission')
ok "and requests none either" "$([ "$requested" -eq 0 ] && echo yes || echo no)" "requests $requested"

# ---- does it open ----

"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -W -n "$ACTIVITY" >/dev/null 2>&1
sleep 4

focused=$("$ADB" shell dumpsys activity activities | tr -d '\r' | grep -c "$PKG")
ok "the app opens and stays open" "$([ "$focused" -gt 0 ] && echo yes || echo no)"

crashes=$("$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c "$PKG")
ok "nothing crashed on the way up" "$([ "$crashes" -eq 0 ] && echo yes || echo no)"

# ---- did the editor load ----

# chromium logs every console message from the WebView, which is how the page
# can report on itself without the app needing a test-only back door.
webview_errors=$("$ADB" logcat -d -s chromium:E 2>/dev/null | grep -ci 'uncaught\|failed to load')
ok "the editor page loaded without errors" "$([ "$webview_errors" -eq 0 ] && echo yes || echo no)" \
  "$("$ADB" logcat -d -s chromium:E 2>/dev/null | grep -i 'uncaught\|failed to load' | head -1 | cut -c1-90)"

# ---- can it open a file ----

"$ADB" shell 'echo "console.log(\"from a real file\"); 21 * 2;" > /sdcard/Download/codepad-check.js' >/dev/null 2>&1
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -a android.intent.action.VIEW \
  -d "file:///sdcard/Download/codepad-check.js" -t "text/plain" -n "$ACTIVITY" >/dev/null 2>&1
sleep 3

view_crashes=$("$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c "$PKG")
ok "opening a file does not crash it" "$([ "$view_crashes" -eq 0 ] && echo yes || echo no)"

"$ADB" shell rm -f /sdcard/Download/codepad-check.js >/dev/null 2>&1

# ---- and back out ----

"$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 1
back_crashes=$("$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c "$PKG")
ok "backing out does not crash it" "$([ "$back_crashes" -eq 0 ] && echo yes || echo no)"

echo
echo "$passed passed, $failed failed."
[ "$failed" -eq 0 ] || exit 1
