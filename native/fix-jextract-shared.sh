#!/bin/bash
# Fix jextract-generated shared.java when the C_LONG layout type is misidentified.
# jextract 25+2-4 on the CentOS7 manylinux toolchain incorrectly declares C_LONG
# as ValueLayout.OfInt on Linux LP64, where C long is 64-bit. This causes a
# ClassCastException at runtime because Linker.canonicalLayouts().get("long")
# returns a ValueLayout.OfLong on those platforms.
set -eu
set -o pipefail

PLATFORM="${1:-${DETECTED_PLATFORM:-unknown}}"
OUTPUT_DIR="${2:-target/generated-sources}"

HYPHEN_COUNT=$(awk -F- '{print NF-1}' <<< "$PLATFORM")
if [ "$HYPHEN_COUNT" -ge 2 ]; then
    PLATFORM_FAMILY="${PLATFORM%-*}"
else
    PLATFORM_FAMILY="$PLATFORM"
fi
PLATFORM_PACKAGE="${PLATFORM_FAMILY//-/_}"

SHARED_FILE="${OUTPUT_DIR}/com/xenoamess/hyperscan_panama/jni/${PLATFORM_PACKAGE}/generated/hyperscan\$shared.java"
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

# Add Linker.Option.critical(true) to the downcall handles of short functions
# that can never call back into Java, cutting per-call FFM overhead.
# A critical(true) downcall that reaches a Java upcall crashes the JVM
# (upcallLinker.cpp guarantee: wrong thread state for upcall). This means:
#   - excluded: hs_scan*, hs_close_stream (match callback), hs_compile* (long)
#   - excluded: hs_free_database, hs_free_scratch, hs_free_compile_error,
#     hs_database_info, hs_serialized_database_info — with a user-installed
#     custom allocator (hs_set_allocator/hs_set_scratch_allocator), freeing or
#     allocating inside these functions invokes the allocator upcall.
# Only functions that write plain outputs and never touch an allocator qualify.
HYPERSCAN_FILE="${OUTPUT_DIR}/com/xenoamess/hyperscan_panama/jni/${PLATFORM_PACKAGE}/generated/hyperscan.java"
if [ -f "$HYPERSCAN_FILE" ]; then
  CRITICAL_FUNCTIONS="hs_version hs_valid_platform hs_database_size hs_scratch_size hs_stream_size"
  awk -v funcs="$CRITICAL_FUNCTIONS" '
    BEGIN {
      n = split(funcs, names, " ")
      for (i = 1; i <= n; i++) critical[names[i]] = 1
    }
    /^[[:space:]]*private static class [A-Za-z_][A-Za-z_0-9]* \{/ {
      in_critical = ($4 in critical) ? 1 : 0
    }
    in_critical && /Linker\.nativeLinker\(\)\.downcallHandle\(ADDR, DESC\)/ {
      sub(/downcallHandle\(ADDR, DESC\)/, "downcallHandle(ADDR, DESC, Linker.Option.critical(true))")
      patched++
    }
    { print }
    END {
      if (patched == 0) {
        print "WARNING: no downcall handles patched with Linker.Option.critical" > "/dev/stderr"
      }
    }
  ' "$HYPERSCAN_FILE" > "$HYPERSCAN_FILE.tmp"
  mv "$HYPERSCAN_FILE.tmp" "$HYPERSCAN_FILE"
  echo "Patched critical(true) downcall handles in ${HYPERSCAN_FILE}"
else
  echo "WARNING: $HYPERSCAN_FILE not found, skipping critical(true) patch" >&2
fi
