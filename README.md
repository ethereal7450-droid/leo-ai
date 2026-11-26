# LEO — Android personal AI assistant (starter project)

This is a real Android Studio source project, not a prebuilt APK.

## Included in v1
- Dark, cyan sci-fi chat interface
- Gemini 2.5 Flash text conversations
- Offline “Hey LEO” keyword detection using Sherpa-ONNX
- Foreground microphone service with persistent notification and Stop control
- Speech-to-text using Android's speech recognition intent
- Text-to-speech replies (UK English locale where installed)
- Local chat history (last 40 messages)
- Basic `open <app name>` command for launchable apps
- API key entry stored in app-private preferences

## Build
1. Install Android Studio (latest stable) on a computer.
2. Extract this ZIP and open the `LEO-Android` folder in Android Studio.
3. Allow Gradle sync. If prompted, install Android SDK Platform 35.
4. Connect an Android phone with USB debugging enabled, or use an emulator.
5. Press Run. To make an APK: Build → Build Bundle(s) / APK(s) → Build APK(s).
6. On the phone, open LEO → Settings → paste a Gemini API key.

Create/manage a Gemini API key at https://aistudio.google.com/

## Important limitations and security
- Wake-word detection runs locally while the foreground service is enabled. After the trigger, command transcription uses the Android speech-recognition provider. Android may stop the service after force-stop, reboot, or aggressive battery management; start it again from the app.
- Voice input uses Android's speech-recognition app/service. Network use and availability depend on the device/provider.
- App launching is restricted to launchable apps visible to Android's package-visibility rules.
- The Gemini API key is stored in app-private preferences, but this is not hardware-backed secret storage. For a public release, move AI calls to a backend so the key is not embedded in the client.
- Do not paste your API key into chats, screenshots, or source control.
- Grant microphone permission only if you want voice input.
- Gemini usage may incur charges or be subject to free-tier limits. Check current terms and quotas.
