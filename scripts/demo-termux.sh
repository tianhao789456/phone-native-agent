#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
python -m unittest discover -s tests
printf 'what time is it?\n/exit\n' | python -m mobile_agent.hosts.cli --mock

