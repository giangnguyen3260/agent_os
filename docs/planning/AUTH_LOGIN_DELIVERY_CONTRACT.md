# First delivery: authentication / login

Status: specified; not implemented or accepted. Branch: `feature/auth-login`.
Audit and planning are on `review/screen-delivery-audit`; no audit PR may claim login completion.

## Design references

- Desktop: Figma `70:6350`, error `70:6387`, loading `70:6421`.
- Tablet: `71:7109`, error `71:7142`, loading `71:7176`.
- Mobile: `71:7913`, error `71:7946`, loading `71:7980`.
- Desktop and mobile screenshot/context inspected directly on 2026-10-18. Tablet still requires context/render inspection before implementation acceptance.
- Use Forest tokens and Angular components. Figma email/password examples must become empty user inputs. Prototype-only illustrative footer and mock success wording must not ship as business state.

## Functional scope and dependencies

SA/BE must implement persisted human accounts and server-side sessions, credential verification and disabled-account handling before FE can claim real login. Do not reuse model agents as human users. Bootstrap the initial admin through an explicit operator command/configuration with no default password or public registration route. Record bootstrap and authentication audit events without credentials/tokens.

Proposed implementation contract: same-origin Spring Security session, HttpOnly cookie, SameSite policy and CSRF protection; HTTPS uses Secure cookies, with an explicit local-development exception for localhost HTTP. Rotate session on authentication; logout invalidates it. Do not store bearer credentials in localStorage. Session duration, lockout/rate-limit limits and concurrent-session policy must be documented before release, not silently inferred from the mockup. Distributed sessions are required for multi-instance deployment; local single-instance validation is not evidence of scalability.

Passwords use an adaptive one-way password hash; reset/invitation tokens are random and stored as digests. Recoverable provider credentials later require authenticated encryption with managed keys. No plaintext password, token or provider secret in logs, responses or fixtures used in normal runtime.

API contract must be reviewed and written before coding: CSRF bootstrap, sign-in, current session and sign-out; stable generic invalid-credential response, validation and unauthorized response format. Reject unauthorized domain API requests and enforce organization membership server-side before any new authenticated navigation can expose data. Authentication alone must never authorize arbitrary organization IDs.

FE login: semantic form, required email/password, accessible labels/autocomplete, submit via Enter, pending state prevents double submit, generic invalid credentials, recoverable network error, successful session refresh and safe internal redirect. Refresh must read actual server session. Protected routes must redirect to login; backend remains the authority.

Forgot-password button belongs to a real recovery flow. Do not ship an inert CTA or fake email-sent confirmation. Either deliver that dependency with tested email transport and token lifecycle, or mark login delivery incomplete until the adjacent recovery ticket is accepted. Similarly workspace selection must use actual memberships, never a fixed organization UUID.

## Required QC evidence

1. Empty database -> Flyway migrations -> explicit initial admin bootstrap -> account persists after restart.
2. Correct credentials create a real session; incorrect/unknown credentials return indistinguishable public errors; disabled user cannot sign in.
3. No raw password in database or logs; changing stored hash invalidates prior credential assumptions according to documented policy.
4. Session cookie flags verified in local and HTTPS deployment; session ID changes on login; logout/reload cannot access protected API.
5. CSRF missing/invalid requests rejected for state changes; legitimate form succeeds; unauthorized organization access denied server-side.
6. Browser E2E: success, validation, wrong password, network failure, repeated submit, expired session, unsafe return URL, refresh and back navigation.
7. Desktop 1440, tablet 768, mobile 390 plus narrow viewport/keyboard: no clipping, visible focus, adequate contrast, 44px touch targets; compare actual screenshots against node references.
8. Google style, backend tests, FE meaningful tests/build and Docker integration pass on the exact branch commit. Sonar temporarily paused by product-owner request; never report it passed.

BA signs requirement coverage, designer signs visual results, QC signs functional/security evidence independently. No automatic Done from build or number of files changed.
