# FPS unlock test logs

Place diagnostic files from each FPS-unlock test in this folder.

## What to upload

For each test, add the files you can obtain:

- `Geode-YYYY-MM-DD-HH-MM-SS.log`
- `launcher_diagnostics_YYYYMMDD_HHMMSS.jsonl`

If you only have one of the files, upload that one.

## Naming

Use the test date/time in the filename. If testing more than one APK, include the APK version or commit short SHA, for example:

`Geode-2026-11-15-143000-fps-unlock-8eefae8.log`

## Before uploading

Check the files for private information (account identifiers, tokens, email addresses, or other personal data) and redact it if present. Do not upload passwords, access tokens, or signing keys.

## Test notes

When possible, add a short note in the commit description or a separate `test-notes.md` with:

- Device model and Android version
- APK version or commit tested
- Whether FPS felt capped or smoother
- Whether stutters, rendering issues, or crashes occurred

These logs are for diagnosing the FPS-unlock test.