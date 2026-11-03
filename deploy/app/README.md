# App updates

The Pod app checks `https://<your server>/app/latest.json` and offers family
members a one-tap update. This folder holds that file and the signed APK.
Publish a release with `tools/publish-release.ps1` (Windows), or by hand as described
under "Publishing updates" in the main README. Copy the APK first and `latest.json` last.

`latest.json`:

```json
{ "versionCode": 4, "versionName": "0.4.0", "notes": "What's new...", "apk": "pod-0.4.0.apk", "sha256": "<hex>" }
```

Safety: phones only install an update signed with the same release key as the
app they already have. Android enforces this, so a tampered APK is rejected.
