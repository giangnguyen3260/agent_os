# ADR-0003: Google coding standards and independent QC

Status: accepted direction from user, 2026-10-15. Toolchain execution and server setup tracked separately.

Use Google Java Style enforced by Spotless/google-java-format, Google TypeScript style through gts and its formatter configuration. Keep Angular compiler options rather than replacing them with a Node-focused tsconfig. Sonar analysis covers BE and FE and waits for the configured server gate; absent server/token/coverage evidence is Blocked, never Pass. Independent QC owns cases, defect severity, verification and retest. Developers cannot mark unexecuted checks passed.

For this first small skeleton compatibility slice, move Angular19 baseline to Angular21 LTS with TypeScript5.9 and Node24; verify package metadata and run the compiler/build. Angular22 requires TypeScript6; adopting it is separate from this style/tooling slice. This decision does not certify all transitive dependencies or the backend Boot baseline for production.

UI tokens remain those approved in Figma. Tests should exercise behavior and negative outcomes, not merely repeat implementation logic. Auth, session, cryptographic storage, production authorization, load and recovery remain separate implementation slices; foundation code is not a secure production release.
