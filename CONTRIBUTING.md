# Contributing

Mobile Agent is an experimental personal project. Contributions are welcome when they are focused, reproducible, and easy to review.

## Ground Rules

- Keep safety-sensitive behavior explicit. Do not make destructive, account, payment, messaging, or privacy-sensitive actions easier to trigger accidentally.
- Keep phone-native behavior central. ADB is useful for development, but the project direction is Android Host App plus AccessibilityService first.
- Prefer small PRs with tests or a clear manual verification note.
- Do not commit local runtime data, screenshots containing private content, API keys, Android `local.properties`, Gradle build output, or `.mobile-agent` task state.

## Development Setup

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install -U pip
python -m pip install -e ".[dev]"
python -m pytest
```

For Android:

```sh
cd android-host
./gradlew assembleDebug
```

Use JDK 17 and Android SDK API 35.

## Pull Request Checklist

- Explain what changed and why.
- Include test output or manual verification steps.
- Keep generated files out of the diff.
- Update README or docs when behavior, setup, or user-facing tools change.

## Issue Reports

Please include:

- OS and Python version;
- Android version and device model, if phone behavior is involved;
- exact command or app action;
- expected result;
- actual result;
- relevant logs with secrets removed.
