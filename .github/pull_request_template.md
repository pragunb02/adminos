## What and why
<!-- Task ID, what changed, decision(s) -->

## Security gate (docs/ENGINEERING.md §4a, D-100): required for every feature
- [ ] Threat review written in the ticket ("Security" section)
- [ ] Unit tests with hostile input; fail closed on errors
- [ ] Route tests: auth required (route inventory passes), other user's data is 404, CSRF/Origin, limits
- [ ] Playwright attack specs for any new screen or flow
- [ ] No secrets / personal data / raw message text in code, fixtures or logs
- [ ] No new public listener or widened bind
- [ ] New dependencies: licence, latest stable, no known CVE
- [ ] Sensitive new data encrypted at rest, or justified
- [ ] AI: off by default, minimum data, outputs validated (D-67)

## Verification
<!-- Test counts: API unit/integration, Kover, web unit/coverage, Playwright -->
