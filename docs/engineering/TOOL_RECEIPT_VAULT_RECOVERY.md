# Lost committed approval reply — real backend acceptance

The required Vault graph browser flow now intercepts only the root Tool approval POST. Playwright forwards the original request/cookies/CSRF to the real backend with network retries disabled, requires actual HTTP200 and records its returned fingerprint, then aborts delivery to the browser. It never fabricates a successful write or substitutes an API fixture.

The UI must stay pending, explicitly GET the exact retained receipt, receive real HTTP200 with the committed fingerprint, and display historical receipt text. It must not expose a Start link from the recovered receipt. The test checks one approval POST, returns through Run details, separately chooses Start and continues the existing approved Tool/worker/provider diamond. Existing native assertions still require exactly one approval/effect and zero actual Tool charge, before provider handoff, plus four model requests/usage receipts, dependency release, pause/resume, full graph closure and plan import.

The private test interceptor does not change production networking or retry behavior. Approval-grant issuance remains fixture setup. Provider is controlled/simulated; no paid OpenAI acceptance is established. No schema, product UI or production behavior changes in this slice.

Local JavaScript syntax/diff checks and18 retained mocked approval browser cases passed. Local Docker/PostgreSQL are unavailable: the new real-backend lost-reply scenario remains pending matching-head Vault CI. Prior PR138 passed all six workflows, including native receipt recovery; its Vault run exercised successful approval, not this lost-response route.
