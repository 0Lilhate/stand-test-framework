# Hooks System

> **Status (2026-08-05):** No hooks are currently wired in `.opencode/settings.local.json`. Hook scripts in `.opencode/scripts/hooks/` are kept as opt-in templates — they speak the Claude Code hook protocol, so adapt them before wiring them into opencode. The descriptions below are aspirational until the `hooks` block is added.

## Hook Types

- **PreToolUse**: Before tool execution (validation, parameter modification)
- **PostToolUse**: After tool execution (auto-format, checks)
- **Stop**: When session ends (final verification)

## Auto-Accept Permissions

Use with caution:
- Enable for trusted, well-defined plans
- Disable for exploratory work
- Never use dangerously-skip-permissions flag
- Configure `allowedTools` in `~/.claude.json` instead

## TodoWrite Best Practices

Use TodoWrite tool to:
- Track progress on multi-step tasks
- Verify understanding of instructions
- Enable real-time steering
- Show granular implementation steps

Todo list reveals:
- Out of order steps
- Missing items
- Extra unnecessary items
- Wrong granularity
- Misinterpreted requirements
