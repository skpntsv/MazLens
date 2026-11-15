# Privacy and local data

MazLens has no account, backend, analytics or advertising SDK. Recorder traffic is sent over the selected Wi-Fi Direct route. The recorder protocol uses unencrypted local commands and video; MazLens does not add encryption that the recorder does not support.

The app stores these items in Android's private app directory:

- Downloaded original recordings and their metadata.
- The selected recorder and connection preferences.
- A bounded diagnostic history, including raw replies. A reply may include the recorder password or session token.

The entered password remains in memory. A password returned by the recorder can be present in saved diagnostic history and reused for that recorder. This storage relies on Android's app isolation and device protection; it is not a separate encrypted vault.

Cloud backup and device-transfer backup are disabled. Uninstalling MazLens removes its private data. Use Android sharing to retain a recording outside the app first.

Report export and clipboard copying happen only when requested. Reports include readable text and structured data, and can contain credentials, identifiers and recording names. They are **not anonymized**. Review them before sharing and do not upload raw reports to public issues. Sharing a recording grants the selected app access to that file; no broad storage access is requested.

Nearby-device permission is used for Wi-Fi Direct. On Android 12 and older, Android requires location permission for discovery; MazLens does not request or store the phone's GPS position.
