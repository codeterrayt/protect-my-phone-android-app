# Contributing

Thank you for helping improve Protect My Phone.

- Test only on phones you own or whose users explicitly agreed to the test.
- Keep phone numbers, precise locations, SMS payloads, QR codes, keys and debugging credentials out of public reports and commits.
- Discuss substantial changes in an issue first. Small fixes can go straight to a pull request.

## Report a bug

Include the app version, Android version, device model, reproduction steps, expected result and actual result. Mention which optional permissions were enabled. Use fictional examples and redact logs. Report vulnerabilities privately using [SECURITY.md](SECURITY.md).

## Submit a change

1. Fork the repository and create a focused branch.
2. Make the smallest useful change. Explain changes to SMS charges, permissions, pairing or data handling.
3. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest` (Windows: `.\gradlew.bat`). Add meaningful tests for changed security or protocol behavior.
4. Describe the change and validation in the pull request. Include redacted screenshots for UI changes.

Do not commit signing keys, local credentials, device dumps, generated APKs or build output. Release binaries belong in GitHub Releases.

Contributions are licensed under the project's Apache-2.0 license. Please keep discussions respectful and practical.
