#!/bin/sh
# Installs the usr:console-1.0 feature into a Liberty installation by copying the
# bundle and the feature manifest into the user extension directory:
#
#   <usr>/extension/lib/com.ibm.liberty.admin.console_<version>.jar
#   <usr>/extension/lib/features/com.ibm.liberty.admin.console-1.0.mf
#
# These are the same files featureUtility installs from console-1.0.esa.
#
# <usr> is $WLP_USER_DIR when it is set, otherwise <wlp>/usr.
#
# Usage: scripts/install-feature.sh /path/to/wlp
# Uses the files from the last build (mvn package); without a build it takes them
# from dist/console-1.0-usr-extension.zip.
set -eu

if [ $# -ne 1 ] || [ ! -x "$1/bin/server" ]; then
    echo "Usage: $0 /path/to/wlp" >&2
    echo "  (the directory that contains bin/server)" >&2
    exit 1
fi
WLP=$(cd "$1" && pwd)
USR=${WLP_USER_DIR:-$WLP/usr}

HERE=$(cd "$(dirname "$0")/.." && pwd)
FEATURE_DIR=$HERE/console-feature/target
BUNDLE=$(ls "$FEATURE_DIR"/stage/bundles/com.ibm.liberty.admin.console_*.jar 2>/dev/null | head -n 1 || true)
MANIFEST=$FEATURE_DIR/feature/console-1.0.mf
if [ -z "$BUNDLE" ] || [ ! -f "$MANIFEST" ]; then
    ZIP=$HERE/dist/console-1.0-usr-extension.zip
    if [ ! -f "$ZIP" ] || ! command -v unzip >/dev/null 2>&1; then
        echo "Feature not built yet. Run: mvn package" >&2
        exit 1
    fi
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    unzip -q "$ZIP" -d "$TMP"
    BUNDLE=$(ls "$TMP"/wlp/usr/extension/lib/com.ibm.liberty.admin.console_*.jar | head -n 1)
    MANIFEST=$TMP/wlp/usr/extension/lib/features/com.ibm.liberty.admin.console-1.0.mf
    echo "Using $ZIP"
fi

mkdir -p "$USR/extension/lib/features"
# Remove other versions of the bundle so only one is installed
for old in "$USR"/extension/lib/com.ibm.liberty.admin.console_*.jar; do
    if [ -e "$old" ] && [ "$(basename "$old")" != "$(basename "$BUNDLE")" ]; then
        rm -f "$old"
    fi
done
cp "$BUNDLE" "$USR/extension/lib/"
cp "$MANIFEST" "$USR/extension/lib/features/com.ibm.liberty.admin.console-1.0.mf"

echo "Installed usr:console-1.0 into $USR/extension"
echo "Add <feature>usr:console-1.0</feature> to server.xml (see samples/server.xml)."
