#!/usr/bin/env bash
# Builds Serene TV without Gradle: aapt2, javac, d8, zipalign, apksigner.
# Usage: JAVA_HOME=<JDK 17> ./build.sh      (ANDROID_HOME defaults to ~/Android/Sdk)
# Files in local/ (git-ignored) replace the same files in assets/, e.g. local/bootstrap.json for your own
# first-run Home order, wallpaper and rotation.
# Output: build/serene-tv.apk, signed with signing/debug.keystore so it updates the installed app.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="$HERE/build"
KEYSTORE="${KEYSTORE:-$HERE/signing/debug.keystore}"
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"
[[ -f "$KEYSTORE" ]] || { echo "Missing $KEYSTORE: updates must be signed with the key the TV already trusts." >&2; exit 1; }

rm -rf "$OUT/work" "$OUT/assets" && mkdir -p "$OUT/work/gen" "$OUT/work/classes" "$OUT/work/dex"
cp -r "$HERE/assets" "$OUT/assets"
[[ -d "$HERE/local" ]] && cp -r "$HERE/local/." "$OUT/assets/"
"$TOOLS/aapt2" compile --dir "$HERE/res" -o "$OUT/work/res.zip"
"$TOOLS/aapt2" link -o "$OUT/work/base.apk" --manifest "$HERE/AndroidManifest.xml" -I "$PLATFORM" \
    -A "$OUT/assets" --java "$OUT/work/gen" --min-sdk-version 26 --target-sdk-version 30 \
    -0 ttf "$OUT/work/res.zip"
javac -Xlint:-options --release 11 -classpath "$PLATFORM" -d "$OUT/work/classes" \
    $(find "$HERE/src" "$OUT/work/gen" -name '*.java')
"$TOOLS/d8" --release --min-api 26 --lib "$PLATFORM" --output "$OUT/work/dex" $(find "$OUT/work/classes" -name '*.class')
cp "$OUT/work/base.apk" "$OUT/work/unaligned.apk"
(cd "$OUT/work/dex" && zip -q -j "$OUT/work/unaligned.apk" classes.dex)
"$TOOLS/zipalign" -f -p 4 "$OUT/work/unaligned.apk" "$OUT/work/aligned.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/serene-tv.apk" "$OUT/work/aligned.apk"
rm -f "$OUT/serene-tv.apk.idsig"
ls -l "$OUT/serene-tv.apk"
