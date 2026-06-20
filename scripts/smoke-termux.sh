#!/data/data/com.termux/files/usr/bin/sh
set -eu

curl -s http://127.0.0.1:8787/health
printf "\n"
curl -s -X POST http://127.0.0.1:8787/chat \
  -H 'content-type: application/json' \
  -d '{"message":"what time is it?"}'
printf "\n"

