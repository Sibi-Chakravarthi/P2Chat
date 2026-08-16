#!/usr/bin/env bash
cd "$(dirname "$0")"

echo "==================================================="
echo "  P2Chat Sender Setup & Launcher"
echo "==================================================="

if ! command -v python3 &> /dev/null; then
    echo "[ERROR] python3 is not installed!"
    exit 1
fi

if [ ! -d ".venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv .venv
    source .venv/bin/activate
    echo "Installing dependencies..."
    pip install -r requirements.txt
else
    source .venv/bin/activate
fi

python3 sender.py "$@"
