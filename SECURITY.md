# Security policy

AdminOS is a personal, single-user, self-hosted project (D-78). It is not offered as a service, and there are no
released versions: only the latest `main` is maintained.

## Reporting a vulnerability

Please report privately through GitHub's **[Report a vulnerability](https://github.com/pragunb02/adminos/security/advisories/new)**
(private vulnerability reporting). Don't open a public issue or pull request for a security problem.

Include what you found, how to reproduce it, and what an attacker could do with it. Expect an acknowledgement within
a week. Accepted issues are fixed on `main` and credited if you want; declined ones get a short reason.

## Scope

In scope: the code in this repository (API, workers, statement parser, web app, SMS reader, CI and container
configuration). Out of scope: anyone's own deployment, and findings that need physical access to the owner's devices.

How the project tests itself is described in [`docs/security/PENTEST.md`](docs/security/PENTEST.md) and
[`docs/ENGINEERING.md`](docs/ENGINEERING.md) §4a.
