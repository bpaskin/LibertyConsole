#!/bin/sh
# Installs the usr:console-1.0 feature into a Liberty installation by copying the
# bundles and the feature manifests into the user extension directory:
#
#   <usr>/extension/lib/com.ibm.liberty.admin.console_<version>.jar
#   <usr>/extension/lib/com.ibm.liberty.admin.console.javax_<version>.jar
#   <usr>/extension/lib/features/com.ibm.liberty.admin.console-1.0*.mf
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
ZIP=$FEATURE_DIR/console-1.0-usr-extension.zip
if [ ! -f "$ZIP" ]; then
    ZIP=$HERE/dist/console-1.0-usr-extension.zip
fi
if [ ! -f "$ZIP" ] || ! command -v unzip >/dev/null 2>&1; then
    echo "Feature not built yet, or unzip is unavailable. Run: mvn package" >&2
    exit 1
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP" -d "$TMP"
LIB=$TMP/wlp/usr/extension/lib
# Check the complete package before changing the installation.
for adapter in com.ibm.liberty.admin.console com.ibm.liberty.admin.console.javax; do
    set -- "$LIB"/"$adapter"_*.jar
    if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
        echo "Incomplete console feature package: expected one $adapter bundle" >&2
        exit 1
    fi
done
for file in com.ibm.liberty.admin.console-1.0.mf \
    com.ibm.liberty.admin.console-1.0.javax.mf com.ibm.liberty.admin.console-1.0.jakarta.mf; do
    if [ ! -f "$LIB/features/$file" ]; then
        echo "Incomplete console feature package: $file is missing" >&2
        exit 1
    fi
done
mkdir -p "$USR/extension/lib/features"
# Remove older console bundles, including the previous single-WAB installation.
for old in "$USR"/extension/lib/com.ibm.liberty.admin.console_*.jar \
    "$USR"/extension/lib/com.ibm.liberty.admin.console.javax_*.jar; do
    if [ -e "$old" ] && [ ! -f "$LIB/$(basename "$old")" ]; then
        rm -f "$old"
    fi
done
cp "$LIB"/*.jar "$USR/extension/lib/"
cp "$LIB"/features/*.mf "$USR/extension/lib/features/"

echo "Installed usr:console-1.0 into $USR/extension"
echo "Add <feature>usr:console-1.0</feature> to server.xml (see samples/server.xml)."
