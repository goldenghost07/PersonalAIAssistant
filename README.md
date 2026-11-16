# Personal AI Assistant (Android)

A native Kotlin + Jetpack Compose Android starter app with configurable providers:
- OpenAI-compatible Chat Completions endpoints (OpenAI, OpenRouter, DeepSeek, Groq, and compatible gateways)
- Anthropic Messages API
- Google Gemini generateContent API

## Build an APK
1. Install Android Studio (current stable) with Android SDK Platform 35.
2. Open this folder in Android Studio and allow Gradle sync.
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Android Studio will show the generated APK location (usually `app/build/outputs/apk/debug/app-debug.apk`).

A GitHub Actions workflow is included to build a debug APK after you push this project to a GitHub repository.

## Configure
Open the app → gear icon → choose provider → enter Base URL, API key, and model ID → Save settings.
For OpenAI-compatible providers, use the provider's documented base URL and a model ID available to your account.

## Security and limitations
- API keys are stored using AndroidX EncryptedSharedPreferences on-device.
- Network calls are sent directly from the phone to the configured provider.
- Do not share your API key or install APKs from untrusted sources.
- This first version sends normal text chat requests; streaming, image attachments, voice, and multi-chat persistence are not implemented yet.
- Some providers require additional headers or custom request formats; this version supports standard OpenAI-compatible, Anthropic Messages, and Gemini generateContent formats.
- For production use, consider a small backend proxy so API keys are not exposed to app instrumentation or rooted-device inspection. Encrypted local storage is not equivalent to server-side secret protection.
