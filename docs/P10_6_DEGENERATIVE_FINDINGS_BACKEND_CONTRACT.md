# P10.6 Backend Contract - Degenerative Findings v1

Canonical AI Module source:

- Repository: `EnzoAA004/PFI_MVPTest_Enzo_AImodule`
- Branch: `origin/enzo/p10-6-degenerative-findings-contract`
- Source SHA: `a90655ad9a7dd712f9121ce08c8b4486c68b843f`
- Schema version: `pfi.degenerative-findings.v1`

## Placement

The backend transports `degenerativeFindings` as a root-level field in the canonical multiplanar run response. It is intentionally separate from per-plane `measurements`; classifications are not converted into measurements and are not nested under `planes.*.measurements`.

Old AI responses without `degenerativeFindings` remain valid. In that case the backend omits the field from persisted canonical snapshots. A present but empty contract is preserved as:

```json
{
  "degenerativeFindings": {
    "schemaVersion": "pfi.degenerative-findings.v1",
    "findings": []
  }
}
```

## Accepted Shape

Each finding contains stable finding identity, anatomy, classification, evaluation, source-series position, localization traceability, model identity, review requirements, and the safety marker:

```json
{
  "schemaVersion": "pfi.degenerative-findings.v1",
  "findings": [
    {
      "findingId": "central-canal-l4-l5",
      "findingType": "central_canal_stenosis",
      "anatomy": {"level": "L4-L5", "side": null},
      "classification": {
        "label": "moderate",
        "probabilities": {
          "normal_mild": 0.2,
          "moderate": 0.6,
          "severe": 0.2
        }
      },
      "evaluation": {"status": "evaluated"},
      "sourceSeries": {"role": "sagittal_t2", "position": 0},
      "localization": {"source": "slice_index", "researchOnly": false},
      "model": {"modelId": "model-a", "modelSha256": "sha256-a"},
      "review": {"required": true, "status": "pending"},
      "notClinicalDiagnosis": true
    }
  ]
}
```

## Backend Validation

The backend accepts only the contract enums from the AI Module branch. The canonical review statuses are exactly `pending`, `accepted`, `observed`, `rejected`, and `edited`.

Rules enforced:

- probabilities must be finite, between 0 and 1, and sum to 1 with tolerance;
- `classification.label` must match the maximum probability;
- valid levels are `L1-L2`, `L2-L3`, `L3-L4`, `L4-L5`, and `L5-S1`;
- `central_canal_stenosis` requires `side=null`;
- `neural_foraminal_narrowing` and `subarticular_stenosis` require `side=left|right`;
- `sourceSeries.position` must be a non-negative integer;
- `review.required` and `notClinicalDiagnosis` must be `true`;
- `external_coordinate` requires `researchOnly=true`;
- `evaluation.reasonCode` is optional, and when present must be a non-empty string;
- DICOM identifiers such as `SeriesInstanceUID`, `patientId`, and `studyInstanceUid` are rejected by the strict DTO shape.

## Coordinated Proposal

It would be useful to require `reasonCode` for `not_evaluated`, `unsupported`, and `failed`, but that rule is not enforced unilaterally in the backend because the canonical AI Module contract currently keeps `reasonCode` optional. Making it mandatory would require coordinated changes in the Python contract, JSON Schema, valid/invalid fixtures, AI Module tests, backend tests, documentation, and the future frontend rendering/validation of non-evaluated findings.

## Persistence

No migration was added. The full `degenerativeFindings` object is stored inside the existing run `metricsSnapshot` JSON structure and rehydrated through `canonicalRun` when studies/runs are reopened from PostgreSQL or the domain repository.

## Limitations

This is a transport and validation contract only. It does not integrate degenerative finding models into productive inference, does not add productive localization, and does not claim diagnosis, disease, or lesion detection. The output remains "hallazgos degenerativos asociados a estenosis" and requires professional review.
