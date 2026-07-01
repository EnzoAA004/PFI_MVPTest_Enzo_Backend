#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-pfi-backend}"
AI_SERVICE_URL="${PFI_AI_SERVICE_URL:-http://host.docker.internal:8000}"
CORS_ORIGINS="${PFI_CORS_ALLOWED_ORIGINS:-http://localhost:5173,http://localhost:3000}"

docker build -t "${IMAGE_NAME}" .

docker run --rm -p 8080:8080 \
  -e PFI_AI_SERVICE_URL="${AI_SERVICE_URL}" \
  -e PFI_AI_TIMEOUT_SECONDS="${PFI_AI_TIMEOUT_SECONDS:-60}" \
  -e PFI_CORS_ALLOWED_ORIGINS="${CORS_ORIGINS}" \
  "${IMAGE_NAME}"
