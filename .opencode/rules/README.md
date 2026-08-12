# Rules

## Structure

Rules are organized into a **common** layer plus **language-specific** directories for the languages used in this project (Java/Kotlin):

```
rules/
├── common/          # Language-agnostic principles
│   ├── agents.md
│   ├── code-review.md
│   ├── coding-style.md
│   ├── development-workflow.md
│   ├── git-workflow.md
│   ├── hooks.md
│   ├── patterns.md
│   ├── performance.md
│   ├── security.md
│   └── testing.md
├── java/            # Java / Spring Boot specific
└── kotlin/          # Kotlin / Android / KMP specific
```

- **common/** contains universal principles — no language-specific code examples.
- **java/** and **kotlin/** extend the common rules with framework-specific patterns, tools, and code examples. Each file references its common counterpart.

## Rules vs Skills

- **Rules** define standards, conventions, and checklists that apply broadly (e.g., "80% test coverage", "no hardcoded secrets").
- **Skills** (`.opencode/skills/` directory) provide deep, actionable reference material for specific tasks (e.g., `springboot-tdd`, `jpa-patterns`, `kotlin-coding-standards`).

Language-specific rule files reference relevant skills where appropriate. Rules tell you *what* to do; skills tell you *how* to do it.

## Rule Priority

When language-specific rules and common rules conflict, **language-specific rules take precedence** (specific overrides general).

- `rules/common/` defines universal defaults applicable to all projects.
- `rules/java/` and `rules/kotlin/` override those defaults where language idioms differ.
