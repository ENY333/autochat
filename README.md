# AI Pixel Chat Studio Android v5 — multi-user slot prototype

**Not a compiled APK and not verified on a physical device.** Use GitHub Actions to build a debug APK; compilation has not been validated here.

## Workflow

1. Enable Accessibility permission, open a chat app's conversation list.
2. Open the region editor using the current accessibility screenshot. Draw a `slot` rectangle around each conversation row (user A, B, etc). Also draw a `contact` rectangle over the chat header and a `messages` rectangle over the message area. These regions currently use shared screen coordinates; they are not per-app layouts.
3. Enter exact user display name, app package, and zero-based slot region index. Save each user separately.
4. Enter allowed packages and HTTPS backend URL. Set send button view ID if supported by the chat app. Enable auto-send only after draft testing.
5. Start scanning. On accessibility events, OCR each slot, tap the row only when the configured name is recognized, verify the header OCR, read the messages, deduplicate, and call backend AI with `package|user` conversation ID. The bot can attempt to send via Accessibility and return to the list.
6. Stop immediately with the app's emergency stop control.

## Known limitations

- **Prototype, not production-ready.** Screenshot capture and OCR are asynchronous and event-driven, not a continuous streaming loop. Some apps won't emit new events while idle.
- The slot rectangle must remain in the same position. A reordered/scrolled list may be missed; no scrolling or discovery of offscreen chats.
- Chat header verification and message OCR are not atomic. Screen changes between checking and sending could still cause a mis-send. Use draft mode until app-specific UI verification is implemented.
- The screenshot editor is based on the screen currently visible when capture is requested; the editor may capture its own app if the user switches away from the target.
- The selected chat must expose exactly one editable field and an identifiable send button; otherwise no automatic send occurs.
- The app does not provide an overlay for region editing inside third-party apps.
- OCR uses ML Kit Latin recognition. Arabic, Chinese, etc. require extra models.
- Backend stores conversations in memory and needs authenticated HTTPS deployment. Never embed an OpenAI key in the APK.
- Android Accessibility service must be explicitly enabled; Android may restrict or stop the service.

## Build APK with GitHub Actions

Upload the **contents** of this folder into a GitHub repository, including `.github/workflows/build-apk.yml`. Go to Actions → Build Android APK → Run workflow. If it succeeds, download `AI-Pixel-Chat-Studio-debug-apk` from Artifacts. Debug APKs are not suitable for public release.

## Backend

Set `OPENAI_API_KEY` in the server environment. Run `pip install -r backend/requirements.txt`, then `uvicorn server:app` from the backend folder. Put the backend behind authenticated HTTPS and persistent storage before real-world use. The server example is for local development only.
