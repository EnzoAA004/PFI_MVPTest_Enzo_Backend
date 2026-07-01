#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BACKEND_BASE_URL:-http://localhost:8080}"

echo "GET ${BASE_URL}/api/ai/health"
curl -fsS "${BASE_URL}/api/ai/health"
echo

echo "GET ${BASE_URL}/api/ai/models"
curl -fsS "${BASE_URL}/api/ai/models"
echo

echo "POST ${BASE_URL}/api/ai/pipeline/run"
PIPELINE_RESPONSE="$(curl -fsS -X POST "${BASE_URL}/api/ai/pipeline/run" \
  -H "Content-Type: application/json" \
  -d '{"caseId":"case-001","plane":"sagittal","modelKey":"baseline","inputPath":"studies/case-001"}')"
echo "${PIPELINE_RESPONSE}"
echo

RUN_ID="$(printf '%s' "${PIPELINE_RESPONSE}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("runId", "run-001"))')"

echo "PATCH ${BASE_URL}/api/ai/review/${RUN_ID}"
curl -fsS -X PATCH "${BASE_URL}/api/ai/review/${RUN_ID}" \
  -H "Content-Type: application/json" \
  -d '{"status":"observado","notes":"Smoke test local","reviewer":"smoke"}'
echo
