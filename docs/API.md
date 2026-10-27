# API

The OpenAPI document is served at `/v3/api-docs`, and Swagger UI at
`http://localhost:8080/swagger-ui.html` when the stack is running. All amounts are integer minor
units (`…Minor`, paise) with an ISO-4217 `currency`. Authenticate with
`POST /api/auth/login`, then send `Authorization: Bearer <token>`.

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/auth/login` | public | username/password → JWT |
| GET | `/api/auth/me` | any | current user and roles |
| POST | `/api/recon/runs` | supervisor, admin | ingest new files and reconcile `{fromDate?, toDate?, asOfDate?, parallelism?, waitForLedgerSeconds?}` |
| GET | `/api/recon/runs`, `/api/recon/runs/{id}` | any | run history |
| GET | `/api/exceptions` | any | filter by `exceptionClass`, `status` (both comma-separated), `merchantId`, `from`, `to`, `assignee`, `minAmountMinor`, `entityId` (prefix); `page`, `size` ≤ 200, `sort=field,asc\|desc` |
| GET | `/api/exceptions/{id}` | any | detail: exception, recon result, ledger / gateway / bank rows, adjustment, comments, audit history |
| GET | `/api/exceptions/{id}/bank-status` | any | live SOAP `getSettlementStatus` call to bank-sim for the exception's UTR |
| POST | `/api/exceptions/{id}/assign` | analyst+ | `{assignee}`; OPEN/PENDING_EXPECTED → IN_REVIEW |
| POST | `/api/exceptions/{id}/comments` | analyst+ | `{body}` |
| POST | `/api/exceptions/{id}/resolve` | analyst+ | `{resolutionReason, note?}`; reasons: `BANK_CONFIRMED`, `RECOVERED_FROM_GATEWAY`, `LEDGER_CORRECTED`, `DUPLICATE_REFUNDED`, `WRITTEN_OFF`, `FALSE_POSITIVE`, `OTHER` |
| GET | `/api/adjustments?status=PENDING_APPROVAL` | any | adjustment journal |
| POST | `/api/adjustments/{id}/approve` | supervisor, admin | post the adjustment and resolve its exception |
| GET | `/api/dashboard/summary?asOf=` | any | money at risk, by class, aging, auto-fixed counts, last run |
| GET | `/api/dashboard/trend?asOf=&days=14` | any | money at risk per business date |
| GET | `/api/audit` | supervisor, admin | filter by `entityType`, `entityId`, `action`, `actor` |
| GET | `/api/simulator/defaults` | any | default fault rates |
| POST | `/api/simulator/runs` | admin | `{seed, days, paymentsPerDay, merchants, faultRates, startDate?, bankCutoffDate?, resetData=true}` |
| GET | `/api/simulator/runs`, `/api/simulator/runs/{id}` | any | simulation progress |
| GET | `/api/ground-truth/evaluation?simRunId=` | supervisor, admin | precision/recall against the ground truth |
| POST | `/api/admin/reset?scope=all\|results` | admin | clear data (the audit log is always kept) |

SOAP (bank-sim): `POST http://localhost:8082/ws`, WSDL at `/ws/settlement.wsdl`, operation
`getSettlementStatus(utr)` → `CREDITED` | `DUPLICATE_CREDIT` | `NOT_FOUND`, with credit count,
amount, value date and bank ref.

## Error format

Every error is `application/problem+json`:

```json
{ "type": "…/docs/API.md#validation_failed", "title": "Bad Request", "status": 400,
  "detail": "request validation failed", "instance": "/api/exceptions/7/resolve",
  "code": "VALIDATION_FAILED", "timestamp": "2026-10-27T15:00:00Z",
  "errors": [{ "field": "resolutionReason", "message": "must be one of …" }] }
```

| code | status | meaning |
|---|---|---|
| <a id="validation_failed"></a>`VALIDATION_FAILED` | 400 | body or parameter failed validation; see `errors` |
| <a id="bad_request"></a>`BAD_REQUEST` | 400 | malformed JSON, bad parameter type, unknown sort field, out-of-range page size |
| <a id="unauthenticated"></a>`UNAUTHENTICATED` | 401 | missing, invalid or expired token |
| <a id="bad_credentials"></a>`BAD_CREDENTIALS` | 401 | wrong username or password |
| <a id="forbidden"></a>`FORBIDDEN` | 403 | your role does not allow this action |
| <a id="not_found"></a>`NOT_FOUND` | 404 | no such record or endpoint |
| <a id="already_resolved"></a>`ALREADY_RESOLVED` | 409 | the exception is already RESOLVED or AUTO_RESOLVED |
| <a id="adjustment_pending_approval"></a>`ADJUSTMENT_PENDING_APPROVAL` | 409 | resolve blocked: a supervisor must approve the adjustment first |
| <a id="adjustment_not_pending"></a>`ADJUSTMENT_NOT_PENDING` | 409 | the adjustment is not awaiting approval |
| <a id="concurrent_modification"></a>`CONCURRENT_MODIFICATION` | 409 | optimistic-lock conflict; reload and retry |
| <a id="run_in_progress"></a>`RUN_IN_PROGRESS` | 409 | a reconciliation run is already executing |
| <a id="simulation_running"></a>`SIMULATION_RUNNING` | 409 | a simulation is already executing |
| <a id="no_utr"></a>`NO_UTR` | 409 | the exception has no settlement reference to look up |
| <a id="upstream_unavailable"></a>`UPSTREAM_UNAVAILABLE` | 502 | gateway-sim or the bank SOAP endpoint failed |
| <a id="internal_error"></a>`INTERNAL_ERROR` | 500 | unexpected error (logged server-side) |
