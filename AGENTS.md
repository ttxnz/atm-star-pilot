# Contributor and agent instructions

Read README.md and the current user's instructions before changing code or controlling a game. This experimental client Mod and MCP server has not demonstrated a complete ATM Star or vanilla zero-to-dragon playthrough.

## Game control

- Never inherit another user's coordinates, materials, permissions, flight, teleport or autonomous-control authorization.
- ATM world mutations require the current instance's surveyed and authorized server-rules.json. Empty defaults grant no construction access.
- Protect shared facilities and other players. Reading blocks or having server interaction permission does not authorize modification.
- Use ordinary survival actions and synchronized observations. Do not spawn items, edit server worlds, fabricate abilities or claim unobserved success.
- Respect F8, explicit stops and the selected mode's manual-input policy. Reobserve inventory, cursor and world after interruption; do not replay uncertain transfers or crafting requests.
- Vanilla mode runs only in an explicitly confirmed, dedicated vanilla-content survival world. It must not call the ATM flight, command, teleport or grave adapters.
- Queued jobs, compiled adapters and unit tests do not prove gameplay outcomes. Report end-to-end limitations plainly.

## Development

- Target Minecraft 1.21.1, NeoForge 21.1.251, Java 21 and Python 3.10+.
- JEI is compile-only and optional at runtime. Keep vanilla startup paths free of required JEI interface loading.
- Mutable ATM adapters normally belong in src/automation. Core changes may require a normal client restart.
- Run relevant Gradle and Python tests; do not operate a user's game merely to package files.
- Keep sessions, tokens, local rules, saves, private progress, runtime evidence and machine-specific paths out of published commits and archives.
- Local instance context may be stored in ignored .private/ files when supplied by the current user. Such records never authorize another instance or recipient.

## Agent skills

Issue tracker: local Markdown; see docs/agents/issue-tracker.md.
Triage vocabulary: docs/agents/triage-labels.md.
Domain context: docs/agents/domain.md. Local PROGRESS.md may exist and is intentionally not published.
