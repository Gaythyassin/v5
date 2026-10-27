#!/usr/bin/env sh
# Geriye uyumluluk: ./logflow betiğine yönlendirir.
exec "$(dirname "$0")/logflow" "$@"
