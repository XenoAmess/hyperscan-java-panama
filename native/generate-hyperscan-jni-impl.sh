#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:?platform required}"
TEMPLATE="${2:?template file required}"
OUTPUT_DIR="${3:?output directory required}"

HYPHEN_COUNT=$(awk -F- '{print NF-1}' <<< "$PLATFORM")
if [ "$HYPHEN_COUNT" -ge 2 ]; then
    PLATFORM_FAMILY="${PLATFORM%-*}"
else
    PLATFORM_FAMILY="$PLATFORM"
fi
PLATFORM_PACKAGE="${PLATFORM_FAMILY//-/_}"

OUT_DIR="${OUTPUT_DIR}/com/xenoamess/hyperscan_panama/jni/${PLATFORM_PACKAGE}"
mkdir -p "${OUT_DIR}"

OUT_FILE="${OUT_DIR}/HyperscanJniImpl.java"

sed "s/@PLATFORM_PACKAGE@/${PLATFORM_PACKAGE}/g" "${TEMPLATE}" > "${OUT_FILE}"

echo "Generated ${OUT_FILE}"
