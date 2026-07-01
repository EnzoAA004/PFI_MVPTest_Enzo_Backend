# E2E Local Test

## 1. Levantar AI Module

Iniciar el modulo FastAPI en `http://localhost:8000`.

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Verificar:

```bash
curl http://localhost:8000/health
curl http://localhost:8000/models
```

## 2. Levantar Backend

Desde este repositorio:

```bash
mvn spring-boot:run
```

El backend queda disponible en `http://localhost:8080`.

## 3. Probar health

```bash
curl http://localhost:8080/api/ai/health
```

Debe devolver el estado del AI Module y `humanReviewRequired=true`.

## 4. Probar modelos

```bash
curl http://localhost:8080/api/ai/models
```

Debe devolver el listado expuesto por el AI Module.

## 5. Probar pipeline

```bash
curl -X POST http://localhost:8080/api/ai/pipeline/run \
  -H "Content-Type: application/json" \
  -d '{"caseId":"case-001","plane":"sagittal","modelKey":"baseline","inputPath":"studies/case-001"}'
```

Guardar el `runId` retornado.

## 6. Probar revision profesional

```bash
curl -X PATCH http://localhost:8080/api/ai/review/run-001 \
  -H "Content-Type: application/json" \
  -d '{"status":"aceptado","notes":"Validado por revision profesional","reviewer":"dr-demo"}'
```

Luego consultar:

```bash
curl http://localhost:8080/api/ai/agent/report/run-001
```

El reporte debe incluir la revision local actualizada.
