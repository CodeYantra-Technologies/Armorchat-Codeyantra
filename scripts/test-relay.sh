#!/bin/sh
set -eu
cd "$(dirname "$0")/../relay"
node --test test/*.test.mjs
