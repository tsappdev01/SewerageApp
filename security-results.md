| # | Result | Test | Evidence |
|---|---|---|---|
| 1.1 | PASS | Every protected endpoint refuses a request without a phone key | 12 endpoints → 403 DEVICE_NOT_REGISTERED |
| 1.2 | PASS | Wrong key refused | 403 DEVICE_NOT_REGISTERED |
| 1.3 | PASS | Another phone's key with an unknown phone id refused | 403 DEVICE_NOT_REGISTERED |
| 1.4 | PASS | Phone A's key does not open phone B | 403 DEVICE_NOT_REGISTERED |
| 1.5 | PASS | Malformed phone id refused | 403 DEVICE_NOT_REGISTERED |
| 1.6 | PASS | Blocked (revoked) phone refused at once | 403 DEVICE_REVOKED |
| 1.7 | PASS | Development sign-in header is stripped by the gateway | X-Dev-User → 403 DEVICE_NOT_REGISTERED |
| 1.8 | PASS | A reader who is not in the reader list is refused | 403 READER_NOT_FOUND |
| 1.9 | PASS | A registration code works only once | second use → 422 REGISTRATION_CODE_INVALID |
| 1.10 | PASS | Only a hash of the phone key is stored | KeyHash is 64 hex, never the key |
| 2.1 | PASS | Another reader cannot download a reader's photo (IDOR) | reading 404/photo 201 by Rashid; Anil → 404 |
| 2.2 | PASS | Another reader cannot add a photo to a reader's reading | Anil → 404 READING_NOT_FOUND |
| 2.3 | PASS | My readings shows only the reader's own | Rashid's reading not in Anil's list |
| 2.4 | PASS | A phone cannot act as another reader (FR-002.7) | Anil's phone with X-Reader Rashid → 403 READER_NOT_ON_THIS_PHONE |
| 2.7 | PASS | A phone cannot send a reading in another reader's name | → 403 READER_NOT_ON_THIS_PHONE |
| 2.5 | PASS | A reading is stored with the phone that sent it | DeviceId = phone A |
| 2.6 | PASS | A body naming another phone is ignored (server-controlled field) | body deviceId=B → stored A |
| 3.1 | PASS | SQL injection in search (%27%20OR%201%3D1%20--) | HTTP 200, 0 results (an empty search gives 12: punctuation is ignored and search runs in memory, not in SQL), no error text |
| 3.1 | PASS | SQL injection in search (1101%27%3B%20DROP%20TABLE%20mr.Device%3B--) | HTTP 200, 0 results (an empty search gives 12: punctuation is ignored and search runs in memory, not in SQL), no error text |
| 3.1 | PASS | SQL injection in search (%25) | HTTP 200, 12 results (an empty search gives 12: punctuation is ignored and search runs in memory, not in SQL), no error text |
| 3.1 | PASS | SQL injection in search (%5B) | HTTP 200, 12 results (an empty search gives 12: punctuation is ignored and search runs in memory, not in SQL), no error text |
| 3.1 | PASS | SQL injection in search (1101%27%20UNION%20SELECT%20name%20FROM%20sys.tables--) | HTTP 400, 1 results (an empty search gives 12: punctuation is ignored and search runs in memory, not in SQL), no error text |
| 3.2 | PASS | SQL injection in meter id | HTTP 404 |
| 3.3 | PASS | SQL injection in zone filter | HTTP 400, 0 meters |
| 3.4 | PASS | Tables intact after injection attempts | mr.Device still there |
| 3.5 | PASS | Malformed JSON → controlled 400 | 400 VALIDATION_FAILED, no internals |
| 3.6 | PASS | Mass assignment: unknown field (status=APPROVED) refused | 400 VALIDATION_FAILED |
| 3.7 | PASS | Wrong data type refused | 400 VALIDATION_FAILED |
| 3.8 | PASS | Unsupported content type refused | HTTP 415 |
| 3.9 | INFO | Script text in a note is stored as text (no HTML is rendered by the API) | HTTP 201  |
| 3.10 | PASS | Unknown code values refused (server-side rules) | 422 INVALID_LOV_CODE |
| 3.11 | PASS | Business rules enforced on the server (more digits than the meter has) | 422 READING_EXCEEDS_REGISTER |
| 3.12 | PASS | A tenant not of this property is refused (FR-006.12) | 409 TENANT_CHANGED |
| 3.13 | PASS | A capture time in the future is refused | 422 CAPTURE_TIME_INVALID |
| 4.1 | PASS | Not reachable from outside: /openapi/v1.json | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /swagger | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /swagger/index.html | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /health/ready | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /api/v1/admin | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /api/v1/devices | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /api/v1/readings/../../health/ready | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /api/v1/meters/..%2F..%2Fhealth%2Fready | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /.env | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /web.config | HTTP 404 |
| 4.1 | PASS | Not reachable from outside: /appsettings.json | HTTP 404 |
| 4.2 | PASS | Unsupported method on a known path | DELETE → 405 |
| 4.3 | PASS | TRACE not allowed | HTTP 405 |
| 4.4 | PASS | Method override header has no effect | POST + override → 405 |
| 4.5 | PASS | Security headers set, server not named | X-Content-Type-Options: nosniff;X-Frame-Options: DENY;Referrer-Policy: no-referrer |
| 5.1 | PASS | Oversized JSON body refused at the gateway | 200 KB → 413 |
| 5.2 | PASS | Deeply nested JSON refused | HTTP 400 |
| 5.3 | PASS | Oversized photo refused | 3 MB → 413 |
| 5.4 | PASS | A file that is not a JPEG is refused (content checked, not the name) | 422 IMAGE_INVALID |
| 5.5 | PASS | A photo whose SHA-256 does not match is refused | 422 IMAGE_HASH_MISMATCH |
| 5.6 | PASS | Path traversal in an image id refused | HTTP 404 |
| 5.7 | PASS | Photos per reading are capped (storage exhaustion) | 9 of 12 extra photos refused TOO_MANY_IMAGES |
| 5.8 | PASS | Per-phone rate limit holds, also with a changing X-Forwarded-For | 160 requests in a burst:     115 200      45 429  |
| 5.9 | PASS | Registration code guessing is rate limited | 20 tries:       5 422      15 429  |
| 6.1 | PASS | Replaying the same reading stores it once | 201 then 200, 1 row |
| 6.2 | PASS | Same id with a changed number is refused (no overwrite) | 409 TRANSACTION_ID_REUSED |
| 6.3 | PASS | A meter already read cannot be read again by a new request | 409 ALREADY_READ |
| 6.4 | PASS | Another reader cannot overwrite it either | 409 ALREADY_READ |
| 6.5 | PASS | No duplicate billing rows in MaintainMeterReading | copies of this reading: 1; shared rows: 0 |
| 6.6 | PASS | At most one live reading per meter per period | meters with two: 0 |
| 7.1 | PASS | Error responses are problem details without internals | checked on every refusal above |
| 7.2 | PASS | No phone keys or passwords in api.log | searched for the test keys and the SQL password |
| 7.2 | PASS | No phone keys or passwords in gateway.log | searched for the test keys and the SQL password |
| 7.3 | PASS | Refused sign-ins are audited with a reason | 38 audit lines (key wrong / unknown / revoked) |
| 7.4 | PASS | Rate-limit refusals are logged at the gateway | 60 lines |
