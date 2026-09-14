#!/bin/sh
set -eu
cd "$(dirname "$0")/../native/armorchat-mlsrs"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed NDK directory}"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
mkdir -p ../../app/src/main/jniLibs
cargo ndk --platform 26 -t arm64-v8a -t x86_64 \
    -o ../../app/src/main/jniLibs build --release --locked
