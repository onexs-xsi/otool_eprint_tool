# Source Publication Preparation Implementation Plan

> **For agentic workers:** Execute with superpowers:executing-plans in this session. Do not publish or push.

**Goal:** Prepare the existing EPrint source for MIT publication without publishing the repository or an application release.

**Architecture:** Remove embedded printer authorization material while retaining user-supplied credentials and the independently implemented protocol. Record third-party origins and licenses, make source builds portable, then sanitize all local Git refs in an isolated mirror after taking a private bundle backup.

**Tech Stack:** Java 17 / Android Gradle Plugin 8.13.0 / Gradle 8.14.3 / Python 3 / git-filter-repo.

**Spec:** User instructions in this conversation: MIT; remove embedded authorization tokens and historical copies; third-party notices and standard build instructions; no repository publication and no application release.

## Global Constraints

- No remote pushes, visibility changes, APK delivery, installation, or version release.
- Keep versionName 1.5.41 and versionCode 47; run tests and native compilation without packaging a changed APK.
- Preserve original Git history and local data in ignored `backup/` before rewriting.
- Keep existing user-supplied credential support; never fall back to embedded credentials.
- Preserve existing open-source attribution and licensing; do not relicense third-party material as MIT.
- Exclude catalog snapshots with unconfirmed redistribution rights from tracked source and history; preserve local files and the loader's existing empty-catalog fallback.

## Review Focus

- Blank, partial, or malformed user credentials must never trigger built-in authorization.
- Old stored default-authorization preferences must have no effect.
- A fresh source checkout must test and compile using Gradle Wrapper and a standard SDK without `.tools`.
- Third-party binary/model identity and licenses must be traceable, without claiming unverified redistribution rights.
- Every local branch/tag/remote-tracking ref must be scanned after rewriting; private backups must stay outside Git.

### Task 1: Remove embedded authorization

- [ ] Back up all refs and local license/document changes.
- [ ] Add credential selection regression tests: no configured credentials; valid explicit credentials; incomplete/invalid credentials rejected; defensive copies.
- [ ] Run the tests and observe the expected missing-interface failure.
- [ ] Remove default token data, API, profile state, persistence, UI and connection-service fallback; retain explicit credentials.
- [ ] Run focused tests, then all JVM tests and Lint.

### Task 2: License, provenance and portable builds

- [ ] Add upstream license texts and notices for vendored headers/libraries, models and direct dependencies; record hashes/source refs for bundled assets.
- [ ] Exclude raw catalog snapshots pending permission; document optional local data and independently written protocol scope.
- [ ] Document Wrapper/JDK/SDK/NDK/CMake setup and optional GLM/Qwen provisioning; parameterize ADB and build tool discovery.
- [ ] Validate a clean tracked-source checkout without the private catalog; compile Java, test classes and JNI, without packaging an APK.

### Task 3: Sanitize history and finalize locally

- [ ] Commit verified source changes locally.
- [ ] Filter an independent mirror: redact embedded credentials in every historical blob and message; remove raw catalog data and private device endpoints; retain ordinary development history.
- [ ] Scan filtered refs and inspect the final tree before updating local refs.
- [ ] Update local refs to verified rewritten commits; preserve remote URL but do not push.
- [ ] Verify clean status, reachable-history audit and local bundle backup; report remote-history limitations explicitly.

## Execution Ledger

- Authorized scope is local preparation; remote history cleanup will remain pending until a separately authorized push.
- History filtering is performed in a disposable independent mirror rather than a worktree sharing the original object database. The primary checkout is used for the source edits so its existing private SDK remains available; no concurrent writers are used.
