# Stale preflight after Tool approval — native acceptance gate

The real Vault graph browser captures a Run Start preflight before choosing Tool preprocessing and requires no selected approval. It retains that model request/revision/fingerprint, then follows the existing committed-lost-reply approval and GET recovery flow.

A fresh preflight must have the exact recovered approval fingerprint and a changed Start fingerprint, while Run revision and model request preview stay identical. Using the actual authenticated browser context and fresh CSRF, the test submits the old fingerprint with a new command key. The real API must return409. A current Run read must remain CREATED at the original revision, with no dispatches, Attempts or Tool effects. This is a negative safety request to the disposable test environment, not a user runtime mutation.

The browser then returns through Run details, reloads Start preview, requires the selected Tool summary and explicitly Starts with the fresh fingerprint. Existing exactly-one Tool approval/effect, pre-provider zero-cost evidence, four provider sends/usage records, dependency context, PAUSE/RESUME and full graph closure/import checks remain. No stale response is relabeled as success and no assertion is disabled.

Local JavaScript syntax/diff checks and24 retained mocked execution/dependency browser cases passed. No Docker/PostgreSQL available locally; new real API409/non-effect proof awaits matching-head Vault CI. Provider is controlled/simulated; no paid OpenAI acceptance, product/migration change, merge or deployment is claimed.
