#!/usr/bin/env bash
# Compiles the hash-pinned requirement files (AOS-622), pip-tools layout (what Dependabot's pip support updates):
#   requirements.in      → requirements.txt       direct pins → every transitive dependency, with SHA-256 hashes
#   requirements-dev.in  → requirements-dev.txt
# Run after changing a .in file: services/pdf-parser/lock.sh
# The image installs requirements.txt with --require-hashes, so a tampered or substituted package fails the build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker run --rm -v "$here:/src" -w /src python:3.14-slim sh -c '
  pip install -q --root-user-action=ignore pip-tools==7.6.2 &&
  pip-compile -q --allow-unsafe --generate-hashes --strip-extras -o requirements.txt requirements.in &&
  pip-compile -q --allow-unsafe --generate-hashes --strip-extras -o requirements-dev.txt requirements-dev.in'
