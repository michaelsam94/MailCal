# MailCal

**MailCal** is an Android app that connects your **Gmail** inbox with your device **calendar**. It reads email on your behalf (after you sign in with Google), looks for meeting-style messages, extracts event details, and lets you add those events to your local calendar—one by one or in bulk. The app is **offline-first**: fetched mail and parsed events are stored locally so you can review them before anything is written to the calendar.

## What it does

1. **Sign in with Google** — Uses Google Sign-In for account access and Gmail API scopes needed to read messages.
2. **Sync inbox** — Fetches new mail via the Gmail API, stores subjects and bodies in a local database, and advances a sync cursor so later runs only pull newer messages.
3. **Detect events in email** — Parses each message in order of reliability:
   - **ICS attachments or calendar parts** when present
   - **ML Kit Entity Extraction** for dates/times in the message text (English model)
   - A **keyword-based fallback** when the message looks like an invitation but structured parsing is thin
4. **Calendar integration** — With `READ_CALENDAR` / `WRITE_CALENDAR` permission, inserts events into a writable calendar on the device (title, start/end, location, meeting links in the description when available).
5. **Background sync** — Uses **WorkManager** to enqueue periodic work so the inbox can stay updated without keeping the app in the foreground.

The main UI shows sync status, how many parsed events are pending, and a list of upcoming pending events with **Add** (single) and **Add all** actions.

## Project structure

Single-module Android app (`:app`). Kotlin packages under `com.michael.mailcal`:

| Package / area | Role |
|----------------|------|
| `MainActivity` | Jetpack Compose UI: sign-in screen vs. home (sync, pending events, add to calendar). Launches calendar permission flow and schedules periodic sync. |
| `core/common` | Shared types such as `AppResult` / `Result` wrappers. |
| `core/database` | **Room** entities, DAO, database factory—emails, parsed events, sync state (tokens, cursors, parser status). |
| `core/network` | **Gmail REST** client and DTOs for messages and metadata. |
| `feature_auth` | Sign-in flow, session/token persistence, `AuthViewModel`. |
| `feature_sync` | `SyncRepositoryImpl` (fetch + store + parse pipeline), `EmailEventParser` (ICS / ML Kit / fallback), `InboxSyncViewModel`. |
| `feature_calendar` | `CalendarRepositoryImpl` writing to `CalendarContract`; presentation helpers for event review. |
| `worker` | `EmailSyncWorker` and runner that enqueue WorkManager jobs. |
| `ui/theme` | Material 3 theme, typography, colors for Compose. |

Root-level assets such as `playstore-assets/` and `mailcal_app_icon.png` are store listing and branding artwork, not loaded by the app binary at runtime except where referenced from resources.

## Libraries and stack

- **Language & tooling** — Kotlin 2.0, Android Gradle Plugin 8.x, **KSP** for Room code generation.
- **UI** — **Jetpack Compose** (BOM), Material 3, Activity Compose, edge-to-edge enabled in `MainActivity`.
- **Architecture** — ViewModels, **Lifecycle** (runtime + ViewModel), coroutines (including `kotlinx-coroutines-play-services` for Tasks → suspend).
- **Persistence** — **Room** (runtime + KTX + compiler via KSP).
- **Background work** — **WorkManager** (`work-runtime-ktx`).
- **Google & Gmail** — **Play services Auth** (Google Sign-In); Gmail access implemented via the project’s HTTP client and stored tokens (see auth layer).
- **On-device NLP** — **ML Kit Entity Extraction** (beta) for date/time extraction from email body text.
- **Tests** — JUnit 4, AndroidX JUnit, Espresso, Compose UI test (Junit4) + preview/debug tooling.

Exact versions live in `gradle/libs.versions.toml`.

## Configuration (local only)

Do **not** commit secrets. Copy or create **`local.properties`** at the project root (it is gitignored) and set:

- **`gmail.client.id`** — OAuth 2.0 client ID for debug / default `BuildConfig.GMAIL_CLIENT_ID`.
- **`gmail.client.id.release`** — OAuth client ID used for release builds.
- **Release signing** (optional, for Play uploads): `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

Register the OAuth client in [Google Cloud Console](https://console.cloud.google.com/) with the correct SHA-1 for your debug/release keystores and package name `com.michael.mailcal`.

## Build

Requires **JDK 11** (as configured in Gradle). From the project root:

```bash
./gradlew assembleDebug
```

Release builds need signing and `gmail.client.id.release` as above.

## Requirements

- **minSdk 24**, **targetSdk 36**
- Calendar permissions for creating events
- Google account and Gmail API consent configuration for your OAuth client

## License

MailCal is open source under the MIT License. The canonical copy lives in [`LICENSE`](LICENSE) at the repository root; the same terms are repeated below for convenience.

MIT License

Copyright (c) 2026 Michael Sam

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
