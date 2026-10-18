# ADR-0002: REST API First

## Status

Accepted for v0.1.

## Decision

AgentOS exposes REST APIs under `/api/v1`.

## Consequences

- Frontend integration is straightforward.
- OpenAPI generation can be added later.
- GraphQL is deferred until query complexity justifies it.
