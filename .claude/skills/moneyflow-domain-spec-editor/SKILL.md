---
name: moneyflow-domain-spec-editor
description: Use when editing Moneyflow_Domain_and_API_Spec.md — covers syncing from the device, the push/verify procedure over the device bridge, and the pre-MVP consolidate-don't-append editing philosophy.
---

# Editing the Moneyflow domain spec

The spec lives at `Moneyflow_Domain_and_API_Spec.md` at the root of this repo. There is no in-place patch — every change means: sync, edit a local copy, push, verify.

## 1. Sync before editing, every session

A local scratch copy from a previous session is not trustworthy — the file may have changed since (edited directly, or by another session), and a brand-new session has no local copy at all. Before making any edit:

- Read the current on-disk/on-device file fresh into a local working copy.
- Never assume a local copy already reflects the current state, even if it was written earlier today.

## 2. Locate before you write

Grep/read the section(s) the change actually touches before editing. Don't append blind — check whether the topic is already documented elsewhere in the spec first, so you don't end up with two descriptions of the same rule drifting apart.

## 3. Editing philosophy: phase-dependent

**Pre-MVP (nothing shipped yet, spec is still settling):** when a feature's documented behavior changes — especially after iterating on a bug fix across several turns — rewrite the section to describe the *current, correct* design cleanly. Do not bolt on a new dated paragraph narrating "this was broken, then fixed, then fixed again" — that's changelog behavior applied too early, and it makes the spec unreadable as a description of how the system actually works today. Replace/merge in place. Only write the section once the design has stopped moving — don't rewrite after every single back-and-forth turn, write it once the fix is verified and stable.

**Post-MVP / shipped:** once real users depend on documented behavior, flip the default — an honest changelog matters more than a clean narrative, since a live API's history is itself useful information. If unsure which phase applies, ask rather than assuming pre-MVP habits still apply.

**Version numbers:** never bump the spec's version number as a side effect of a content edit. Only change it when explicitly asked to.

## 4. Push over the device bridge (when working from a cloud/Cowork session)

1. Copy the edited local file into `/mnt/user-data/outputs/`.
2. Call `device_commit_files` with `stagedPath` (the outputs-dir path) and `devicePath` (the real repo path on the Mac).

(When working directly in this repo — e.g. a Claude Code terminal session with this directory as its working directory — just edit the file in place; no bridge/push step is needed.)

## 5. Verify — never trust "written" alone

`device_commit_files` has a known stale-write bug: it can report `{"written": [...], "rejected": []}` while the on-device file is actually unchanged. After every bridge push:

- Compare byte counts (`wc -c` on both the local file and the on-device file) — they must match exactly.
- Run `git diff --stat` on the repo for that file and confirm the diff shape looks like what's expected (right order of magnitude, not wildly more or fewer lines than the edit implies).
- Grep on-device for a snippet that should now be present, and (when replacing/removing text) a snippet that should now be gone — confirm counts are what's expected (1, 0, etc.).

If any check fails, it's the stale-write bug: retry the identical `device_commit_files` call with `force: true`, then re-run the full verification again. Do not report the edit as done until verification passes independently of the tool's own response.

## 6. Commit messages

- The user runs `git commit` themselves. Only ever draft the message — never commit on their behalf.
- Write one commit message per *settled* thread of work, not one per iteration. If a fix went through several rounds of revision before landing, the commit message describes the final, net design — not a turn-by-turn account of how you got there.
- Follow whatever attribution lines the current session's instructions specify (these can change session to session — don't hardcode a specific session ID or model name from a prior session into this file).
