# peanuts-native

Fork of [igawa6/peanuts-native](https://github.com/igawa6/peanuts-native): Nintendo Switch games running natively on Android dual-screen handhelds such as the AYN Thor. Our copy is `mclarkelauer/peanuts-native` (remote `origin`); `upstream` points at igawa6. Upstream's own `AGENTS.md`, `CONTRIBUTING.md` and `PEANUTS.md` apply inside this repository.

This repository is one project inside the Ayn Thor workspace. Before working here, read the workspace files one level up:

- `../README.md` for what the Ayn Thor is and general information
- `../GUIDELINES.md` for the conventions every repository follows, including how releases reach the device through Obtainium

The subagent for this repository is `peanuts-native-dev`, defined in `../.claude/agents/peanuts-native-dev.md`.

## Project notes

- Work happens on branch `ayn-thor`; `main` tracks upstream.
- Current goal: in the storage menu, add a "Delete" (or "Move") option per game that removes every file Peanuts Native generated for that game (the built app, translated code, shader caches and so on) and returns the game to the unbuilt state, so it shows up again as something that can be built.
- Releases: tag `v<upstream version>-thor.N`, APK attached to a GitHub Release, built by a tag-triggered workflow, so Obtainium can install it. See "Distribution" in `../GUIDELINES.md`.
