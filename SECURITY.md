# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.0.x   | :white_check_mark: |

## Test Docker Transport Hardening

The upstream `docker-java-transport-zerodep:3.7.1`, used by Testcontainers 2.0.5,
embeds components affected by these release-blocking findings:

| Finding | Original embedded component | Replacement |
|---------|-----------------------------|-------------|
| CVE-2026-71290 | Apache HttpClient 5.5.1 | 5.6.5 |
| CVE-2026-54399 | Apache HttpCore 5.3.6 | 5.4.4 |
| CVE-2026-54428 | Apache HttpCore 5.3.6 | 5.4.4 |

Project tests use a locally built replacement from
[`test-support/docker-transport`](test-support/docker-transport/README.md).
It preserves docker-java's transport classes and replaces the embedded Apache
bytecode, metadata and resources using official Maven artifacts. HttpCore 5.4.4
matches the version managed by HttpClient 5.6.5. Ordinary dependency constraints
cannot replace classes already shaded inside the original transport JAR.

Before tests and security scans, build checks verify the embedded versions,
replacement Apache bytecode, unchanged docker-java bytecode and test classpath.
Docker tooling remains absent from the production runtime and published POM;
this support project has no publishing plugin.

The November 25, 2026 runtime and test-runtime scan detected the replacement
components and reported zero vulnerabilities. Scans retain the CVSS 7.0 blocking
threshold, include test dependencies, and fail on analysis errors. No CVE
suppression is applied. Reports are generated under
`build/reports/dependency-check/`; a clean scan reflects the advisory data
available when it runs.

## Reporting a Vulnerability

If you discover a security vulnerability in BoosterQuery, please report it responsibly.

**Do NOT open a public GitHub issue for security vulnerabilities.**

Instead, please email: **dev@chaosguide.com**

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact

We will acknowledge receipt within 48 hours and provide a timeline for a fix.
