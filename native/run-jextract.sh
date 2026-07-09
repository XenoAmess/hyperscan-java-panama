#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:?platform required}"
JEXTRACT_BIN="${2:?jextract binary required}"
OUTPUT_DIR="${3:?output directory required}"
INCLUDE_DIR="${4:?include directory required}"
HEADER_FILE="${5:?header file required}"

HYPHEN_COUNT=$(awk -F- '{print NF-1}' <<< "$PLATFORM")
if [ "$HYPHEN_COUNT" -ge 2 ]; then
    PLATFORM_FAMILY="${PLATFORM%-*}"
else
    PLATFORM_FAMILY="$PLATFORM"
fi
PLATFORM_PACKAGE="${PLATFORM_FAMILY//-/_}"
TARGET_PACKAGE="com.xenoamess.hyperscan_panama.jni.${PLATFORM_PACKAGE}.generated"

mkdir -p "${OUTPUT_DIR}"

"${JEXTRACT_BIN}" \
    --output "${OUTPUT_DIR}" \
    --target-package "${TARGET_PACKAGE}" \
    -I "${INCLUDE_DIR}" \
    "${HEADER_FILE}"

echo "Generated jextract classes for ${TARGET_PACKAGE}"
