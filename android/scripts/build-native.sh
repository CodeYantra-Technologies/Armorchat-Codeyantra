#!/bin/sh
set -eu
cd "$(dirname "$0")/../native/armorchat-vodozemac"
# API belongs to the NDK target, not the Rust triple. Match app minSdk 26.
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed NDK directory}"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
cargo ndk --platform 26 -t arm64-v8a -t x86_64 -t armeabi-v7a \
    -o ../../app/src/main/jniLibs build --release --locked
