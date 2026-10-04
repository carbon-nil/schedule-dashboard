#!/usr/bin/env bash
set -euo pipefail

# Named volumes come up root-owned on first use.
sudo chown "$(id -u):$(id -g)" "$HOME/.cache" "$HOME/.sbt" "$HOME/.ivy2"

# Personal setup (not tracked) runs before the shared one.
if [ -f .devcontainer/postCreate.local.sh ]; then
    bash .devcontainer/postCreate.local.sh
fi

npm ci
npm ci --prefix frontend
