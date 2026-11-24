#!/usr/bin/env bash
# Build DataDial for Wear OS.
#
#   ./build.sh          generate the watch face XML and images, build the debug APKs
#                       (the watch face and the DataDial Stats companion app)
#   ./build.sh run      also install both on the connected watch or emulator and
#                       make DataDial the active watch face
#   ./build.sh test     like run, but with fixed worst-case values (see TEST_VALUES
#                       in tools/generate.py, and DataDial Stats' test values); the
#                       normal XML is written back after
#   ./build.sh release  generate and build the signed release APKs (for GitHub) and
#                       bundles (.aab, for Google Play); needs the release key (see README)
#   ./build.sh log      show the watch face runtime's log (WFF errors show up here)
#   ./build.sh statslog show DataDial Stats' log: each time the system asks it for
#                       a stat (and the value), and each Health Services delivery
#
# Every build checks the watch face with Google's Watch Face Format tools: the XML
# against the format's schema, and the APK's memory footprint against the limits
# Google Play uses. They are downloaded to build/tools the first time; delete that
# folder to get the latest ones.
#
# Uses the JDK bundled with Android Studio unless JAVA_HOME is set, and the
# SDK in the default Android Studio location unless ANDROID_HOME is set. With
# several devices connected, set ANDROID_SERIAL to the one to install on.
set -euo pipefail
cd "$(dirname "$0")"

PACKAGE=io.github.ls6777.datadial
STATS_PACKAGE=$PACKAGE.stats
XML=watchface/src/main/res/raw/watchface.xml
FACE_APK=watchface/build/outputs/apk/debug/watchface-debug.apk
STATS_APK=stats/build/outputs/apk/debug/stats-debug.apk
RELEASE_FACE_APK=watchface/build/outputs/apk/release/watchface-release.apk
RELEASE_STATS_APK=stats/build/outputs/apk/release/stats-release.apk
RELEASE_FACE_AAB=watchface/build/outputs/bundle/release/watchface-release.aab
RELEASE_STATS_AAB=stats/build/outputs/bundle/release/stats-release.aab
TOOLS=build/tools
TOOLS_URL=https://github.com/google/watchface/releases/download/release

: "${JAVA_HOME:=C:/Program Files/Android/Android Studio/jbr}"
: "${ANDROID_HOME:=${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
export JAVA_HOME ANDROID_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
JAVA="$JAVA_HOME/bin/java"

if [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]]; then
    GRADLE=./gradlew.bat
else
    GRADLE=./gradlew
fi

# The Watch Face Format version the manifest declares.
WFF_VERSION=$(grep -A1 'com.google.wear.watchface.format.version' \
    watchface/src/main/AndroidManifest.xml | sed -n 's/.*android:value="\([0-9]*\)".*/\1/p')

runtime_log() {
    "$ADB" logcat --pid="$("$ADB" shell pidof -s com.google.wear.watchface.runtime)"
}

# The path to one of Google's Watch Face Format tools, downloaded if it isn't there yet.
tool() {
    local jar="$TOOLS/$1.jar"
    if [[ ! -f "$jar" ]]; then
        mkdir -p "$TOOLS"
        echo "Downloading $1.jar" >&2
        curl -fsSL -o "$jar.part" "$TOOLS_URL/$1.jar"
        mv "$jar.part" "$jar"
    fi
    echo "$jar"
}

# Check the watch face XML against the format's schema. The validator exits
# with 0 even when the XML is invalid, so its verdict is read from its output.
check_xml() {
    local out
    out=$("$JAVA" -jar "$(tool wff-validator)" "$WFF_VERSION" "$XML" 2>&1) || true
    if ! grep -q "PASSED" <<<"$out"; then
        echo "$out" >&2
        echo "The watch face XML is not valid Watch Face Format $WFF_VERSION." >&2
        exit 1
    fi
}

# Check a watch face APK's memory footprint, with the settings Google suggests
# as close to Google Play's review.
check_memory() {
    local out
    if ! out=$("$JAVA" -jar "$(tool memory-footprint)" --watch-face "$1" \
        --schema-version "$WFF_VERSION" --ambient-limit-mb 10 --active-limit-mb 100 \
        --apply-v1-offload-limitations --estimate-optimization 2>&1); then
        echo "$out" >&2
        echo "$1 failed the memory footprint check." >&2
        exit 1
    fi
}

# Build both debug APKs and check the watch face.
build_debug() {
    check_xml
    "$GRADLE" assembleDebug --console=plain -q
    check_memory "$FACE_APK"
}

# Install DataDial Stats, then the watch face (its default data sources must
# be installed first), switch DataDial Stats' test values on or off ($1), and
# make DataDial the active watch face.
install_and_show() {
    build_debug
    "$ADB" install -r "$STATS_APK"
    "$ADB" install -r "$FACE_APK"
    "$ADB" shell am broadcast -n "$STATS_PACKAGE/.TestValuesReceiver" --ez on "$1" > /dev/null
    "$ADB" shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
        --es operation set-watchface --es watchFaceId "$PACKAGE"
}

case "${1:-}" in
    log)
        runtime_log
        exit
        ;;
    statslog)
        "$ADB" logcat -v time -s DataDialStats
        exit
        ;;
    test)
        # Always put the normal XML back, even if the build fails.
        trap 'python tools/generate.py > /dev/null' EXIT
        python tools/generate.py --test
        install_and_show true
        echo "Test build installed. Run ./build.sh run to go back to live values."
        exit
        ;;
esac

python tools/generate.py

case "${1:-}" in
    "")
        build_debug
        echo "Built $FACE_APK"
        echo "  and $STATS_APK"
        ;;
    run)
        install_and_show false
        ;;
    release)
        check_xml
        # Remove the previous outputs, so an old file can't pass for a new one.
        rm -f "$RELEASE_FACE_APK" "$RELEASE_STATS_APK" "$RELEASE_FACE_AAB" "$RELEASE_STATS_AAB"
        "$GRADLE" assembleRelease bundleRelease --console=plain -q
        check_memory "$RELEASE_FACE_APK"
        echo "Built $RELEASE_FACE_APK"
        echo "  and $RELEASE_STATS_APK"
        echo "Built $RELEASE_FACE_AAB"
        echo "  and $RELEASE_STATS_AAB"
        ;;
    *)
        echo "usage: $0 [run|test|release|log|statslog]" >&2
        exit 2
        ;;
esac
