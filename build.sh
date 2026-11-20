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
#   ./build.sh release  generate and build the release bundles (.aab) for Google Play
#   ./build.sh log      show the watch face runtime's log (WFF errors show up here)
#   ./build.sh statslog show DataDial Stats' log: each time the system asks it for
#                       a stat (and the value), and each Health Services delivery
#
# Uses the JDK bundled with Android Studio unless JAVA_HOME is set, and the
# SDK in the default Android Studio location unless ANDROID_HOME is set.
set -euo pipefail
cd "$(dirname "$0")"

PACKAGE=io.github.ls6777.datadial
STATS_PACKAGE=$PACKAGE.stats

: "${JAVA_HOME:=C:/Program Files/Android/Android Studio/jbr}"
: "${ANDROID_HOME:=${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
export JAVA_HOME ANDROID_HOME
ADB="$ANDROID_HOME/platform-tools/adb"

if [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]]; then
    GRADLE=./gradlew.bat
else
    GRADLE=./gradlew
fi

runtime_log() {
    "$ADB" logcat --pid="$("$ADB" shell pidof -s com.google.wear.watchface.runtime)"
}

# Install DataDial Stats, then the watch face (its default data sources must
# be installed first), switch DataDial Stats' test values on or off ($1), and
# make DataDial the active watch face.
install_and_show() {
    "$GRADLE" assembleDebug --console=plain -q
    "$ADB" install -r stats/build/outputs/apk/debug/stats-debug.apk
    "$ADB" install -r watchface/build/outputs/apk/debug/watchface-debug.apk
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
        "$GRADLE" assembleDebug --console=plain -q
        echo "Built watchface/build/outputs/apk/debug/watchface-debug.apk"
        echo "  and stats/build/outputs/apk/debug/stats-debug.apk"
        ;;
    run)
        install_and_show false
        ;;
    release)
        "$GRADLE" bundleRelease --console=plain -q
        echo "Built watchface/build/outputs/bundle/release/watchface-release.aab"
        echo "  and stats/build/outputs/bundle/release/stats-release.aab"
        ;;
    *)
        echo "usage: $0 [run|test|release|log|statslog]" >&2
        exit 2
        ;;
esac
