# cloud-itonami-isic-889

**Non-Residential Social Work Activities Coordinator** (ISIC Rev.4 class 889)

A coordination-only actor for non-residential community social work: counseling, referrals, and welfare/benefits-application assistance.

## Building & Testing

```bash
# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo simulator
clojure -M:run
```

## Architecture

- **Store** (`socialworkops.store`): String-keyed client directory, append-only ledger
- **Advisor** (`socialworkops.advisor`): Mock + LLM-seam proposal interface
- **Governor** (`socialworkops.governor`): Three HARD checks, scope exclusion, confidence gating
- **Phase** (`socialworkops.phase`): 0→3 staged rollout, phase-gating of ops
- **Operation** (`socialworkops.operation`): langgraph-clj StateGraph
- **Sim** (`socialworkops.sim`): Demo driver

## Scope

**COORDINATION ONLY** (never actuates):
- `:log-client-contact-note` — routine case-contact logging
- `:schedule-appointment` — counseling/referral appointment scheduling
- `:coordinate-referral` — external service referral (coordination only)
- `:coordinate-benefits-application-assistance` — tracking benefits applications (NOT eligibility)
- `:flag-safety-concern` — welfare/safety concerns (always escalates)

**PERMANENTLY OUT-OF-SCOPE** (HARD-blocked):
- Child/adult protective-custody or removal decisions
- Involuntary commitment
- Legal representation
- Clinical diagnosis
- Benefits eligibility determination or award/denial decisions
- Safety-authority enforcement

## License

AGPL-3.0-or-later
