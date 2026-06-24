# Mobile Agent Quick Start for New Users

This guide is for first-time APK users. The goal is to get the minimal usable version running first, then enable advanced capabilities as needed.

## 1. Install the APK

Install the APK directly on your Android phone.

If the system shows a risk warning, that's because this is a self-signed debug APK, not a store package. Only get APKs from people you trust, and don't install builds from unknown sources.

## 2. Enter Your API Key

Open the App and configure your DeepSeek API Key first.

Either method works:

- Tap "Panel" (upper right) → "Config", paste your `sk-...` key in the "Quick API Key Entry" field, then save.
- Or just paste `sk-...` directly into the chat input and send it — the App will automatically save and hide the key from display.

The API Key is stored only in the phone's local app-private configuration. Never share your key in group chats, screenshots, GitHub, documentation, or the APK itself.

To change your key, repeat either method — the new key overwrites the old one.

## 3. Choose a Model

You can select a DeepSeek model in the config page:

- `deepseek-v4-flash`: default recommendation. Fast, low-cost, good for daily Q&A, research, and simple phone tasks.
- `deepseek-v4-pro`: more capable, better for complex planning, code review, long tasks, and multi-step troubleshooting.

You can also toggle thinking mode:

- Thinking ON: default recommendation, good for tasks that need analysis, planning, and troubleshooting.
- Thinking OFF: good for simple Q&A and faster replies.
- Thinking intensity `high`: default.
- Thinking intensity `max`: deeper reasoning, may be slower and consume more.

## 4. First Self-Check

After configuring your key, send this in the chat:

```text
Run a self-check for me
```

If the App reports missing permissions, don't enable everything at once. Turn them on one by one as you need each feature.

## 5. Choosing Permissions

Regular users should remember these:

- Accessibility: enable when the Agent needs to observe the screen, tap buttons, or operate the phone UI.
- Notification Listener: enable when you need "important message watch" and alerts.
- File Access: enable when you need to find, read, or share files.

If you don't need PC control yet, you can skip configuring SSH, MCP, Termux, and Tailscale for now.

## 6. Try These Types of Questions

General Q&A:

```text
Search what this concept means today
```

Find a file:

```text
Find the most recent PDF in my download folder
```

Share a file:

```text
Share this file using the system share panel
```

Reminder:

```text
Remind me to drink water in 30 minutes
```

Phone operation:

```text
Open the accessibility page in Settings
```

## 7. Advanced Capabilities

These can be configured later:

- PC Control: after configuring SSH / MCP, the phone Agent can check PC services, run commands, and transfer files.
- Tailscale: connect to your home PC when the phone is away from home Wi-Fi.
- Termux: for on-device scripting and terminal tasks.
- Scheduled Tasks: trigger reminders, scripts, or Agent tasks on a schedule.
- Speaker Alerts: play notification sounds or voice alerts.

## 8. What to Say When Something Goes Wrong

When you encounter an issue, let the Agent diagnose itself:

```text
What's broken right now? Run a self-check first, then tell me what to do next
```

If it keeps retrying, tell it to stop:

```text
Stop blind retrying, summarize the failure reasons, and tell me what you need from me next
```

## 9. Security Reminders

- Never expose your API Key publicly.
- Don't install APKs forwarded from unknown sources.
- Before letting the Agent delete files, send messages, install/uninstall apps, or run PC commands, confirm that you really want to do it.
- When sharing with friends for testing, each person should use their own API Key.
