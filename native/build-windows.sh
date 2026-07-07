#!/bin/bash

# Build native Windows libraries from Intel Hyperscan source for the Panama project.
# Runs on Git Bash / MSYS2 / WSL, primarily intended for GitHub Actions windows-latest.

set -xeu
set -o pipefail

VERSION="5.4.2"
SHA256="32b0f24b3113bbc46b6bfaa05cf7cf45840b6b59333d078cc1f624e4c40b2b99"
BOOST_SHA256="9de758db755e8330a01d995b0a24d09798048400ac25c03fc5ea9be364b13c93"

THREADS=$(nproc --all)

mkdir -p cppbuild/lib
mkdir -p cppbuild/bin
mkdir -p cppbuild/include/hs
cd cppbuild

curl -L -o hyperscan-${VERSION}.tar.gz https://github.com/intel/hyperscan/archive/refs/tags/v${VERSION}.tar.gz
echo "$SHA256  hyperscan-${VERSION}.tar.gz" | sha256sum -c
tar -xvf hyperscan-${VERSION}.tar.gz
mv hyperscan-${VERSION} hyperscan

curl -L -o boost_1_89_0.tar.gz https://archives.boost.io/release/1.89.0/source/boost_1_89_0.tar.gz
echo "$BOOST_SHA256  boost_1_89_0.tar.gz" | sha256sum -c
tar -zxf boost_1_89_0.tar.gz
mv boost_1_89_0/boost hyperscan/include/boost

cd hyperscan

# Disable flakey sqlite detection - only needed to build auxillary tools anyways.
> cmake/sqlite3.cmake

case $DETECTED_PLATFORM in
windows-x86_64|windows-x86_64-baseline)
  # The upstream Intel Hyperscan CMakeLists always adds unit/tools/chimera,
  # which require PCRE. We only need the runtime/compile libraries, so remove
  # those optional subdirectories.
  sed -i '/add_subdirectory(unit)/d' CMakeLists.txt
  sed -i '/add_subdirectory(tools)/d' CMakeLists.txt
  sed -i '/add_subdirectory(chimera)/d' CMakeLists.txt

  # Intel Hyperscan 5.4.2 does not support AVX-512 on Windows (its MSVC path
  # in cmake/arch.cmake does not set SKYLAKE_FLAG). Build two tiers only:
  # baseline (SSE4.2) and AVX2. The AVX2 build is published under the plain
  # windows-x86_64 classifier.
  case $DETECTED_PLATFORM in
    windows-x86_64-baseline)
      ARCH_FLAGS=""
      BUILD_AVX2=OFF
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
    windows-x86_64)
      ARCH_FLAGS="-arch:AVX2"
      BUILD_AVX2=ON
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
  esac

  cmake -G "Visual Studio 17 2022" -A x64 \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_AVX2=$BUILD_AVX2 \
        -DBUILD_AVX512=$BUILD_AVX512 \
        -DBUILD_AVX512VBMI=$BUILD_AVX512VBMI \
        -DFAT_RUNTIME=off \
        -DPYTHON_EXECUTABLE="$(command -v python)" \
        -DCMAKE_C_FLAGS="$ARCH_FLAGS" \
        -DCMAKE_CXX_FLAGS="$ARCH_FLAGS /EHsc" \
        .

  cmake --build . --config Release --target install -- -maxcpucount:$THREADS

  # CMake installs import libraries into lib/ but the runtime DLLs into bin/.
  # Copy the DLLs alongside the import libraries so the loader can find them.
  cp "$(pwd)/../bin/"*.dll "$(pwd)/../lib/" 2>/dev/null || true
  ;;
*)
  echo "Error: Arch \"$DETECTED_PLATFORM\" is not supported by build-windows.sh"
  exit 1
  ;;
esac

cd ../..

echo "Native Windows libraries built successfully for $DETECTED_PLATFORM."
