# ADR-0001: Start With Modular Monolith

## Status

Accepted for v0.1.

## Decision

AgentOS starts as a modular monolith with explicit module boundaries.

## Consequences

- Faster MVP delivery.
- Easier local development and debugging.
- Future service extraction remains possible around Agent Runtime, Model Gateway, Tool Gateway, and Orchestrator.
