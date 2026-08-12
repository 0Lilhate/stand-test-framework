# Security Guidelines

## Mandatory Security Checks

Before ANY commit:
- [ ] No hardcoded secrets (API keys, passwords, tokens)
- [ ] All user inputs validated
- [ ] SQL injection prevention (parameterized queries)
- [ ] XSS prevention (sanitized HTML)
- [ ] CSRF protection enabled
- [ ] Authentication/authorization verified
- [ ] Rate limiting on all endpoints
- [ ] Error messages don't leak sensitive data

## Secret Management

- NEVER hardcode secrets in source code
- ALWAYS use environment variables or a secret manager
- Validate that required secrets are present at startup
- Rotate any secrets that may have been exposed

## Agent Permission Model (acknowledged risk)

All subagents (planner, java-reviewer, kotlin-reviewer, etc.) have `write: allow` + `edit: allow` — this is **standard for OpenCode agents** and required for them to generate artefacts (review reports, plans, codemaps).

Mitigating controls already in place:
- All subagents have `bash: deny` — cannot execute arbitrary commands
- All subagents have `task: deny` (except `explore`) — cannot spawn other agents
- `orchestrator` is the **only** agent with `task: allow` for spawning
- Read operations (grep, glob, read) are unrestricted — expected for code analysis

**Decision:** Accepted. The `write`/`edit` permission is necessary for agent output, and the `bash`+`task` restrictions provide adequate containment.

## Security Response Protocol

If security issue found:
1. STOP immediately
2. Run language-specific reviewer (**java-reviewer** / **kotlin-reviewer**) on affected code
3. Fix CRITICAL issues before continuing
4. Rotate any exposed secrets
5. Review entire codebase for similar issues
