#!/bin/bash
# Fix jextract-generated shared.java when the C_LONG layout type is misidentified.
# jextract 25+2-4 on the CentOS7 manylinux toolchain incorrectly declares C_LONG
# as ValueLayout.OfInt on Linux LP64, where C long is 64-bit. This causes a
# ClassCastException at runtime because Linker.canonicalLayouts().get("long")
# returns a ValueLayout.OfLong on those platforms.
set -eu
set -o pipefail

SHARED_FILE="${1:-target/generated-sources/com/gliwka/hyperscan/jni/hyperscan$shared.java}"
PLATFORM="${DETECTED_PLATFORM:-unknown}"

if [ ! -f "$SHARED_FILE" ]; then
  echo "WARNING: $SHARED_FILE not found, skipping C_LONG fix" >&2
  exit 0
fi

case "$PLATFORM" in
  windows-*|windows_*|windows)
    EXPECTED_TYPE="OfInt"
    ;;
  *)
    EXPECTED_TYPE="OfLong"
    ;;
esac

# Replace any C_LONG declaration/assignment with the correct type for this platform.
# This is a no-op when jextract already generated the correct code.
if grep -q 'public static final ValueLayout\.Of[A-Za-z]* C_LONG' "$SHARED_FILE"; then
  sed -i "s/public static final ValueLayout\.Of[A-Za-z]* C_LONG = (ValueLayout\.Of[A-Za-z]*) Linker\.nativeLinker()\.canonicalLayouts()\.get(\"long\");/public static final ValueLayout.${EXPECTED_TYPE} C_LONG = (ValueLayout.${EXPECTED_TYPE}) Linker.nativeLinker().canonicalLayouts().get(\"long\");/" "$SHARED_FILE"
  echo "Set C_LONG to ${EXPECTED_TYPE} in ${SHARED_FILE}"
fi
