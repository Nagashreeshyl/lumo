# Installing Lumo on a Mac

Requirements: a Mac with **Apple Silicon** (M1 or later) running **macOS 15 Sequoia** or newer.

Unlike the iPhone app, the Mac app **never expires**. No SideStore, no weekly refresh.

## Install

1. Download **`Lumo-<version>.dmg`** from the [latest release](https://github.com/Nagashreeshyl/lumo/releases/latest).
2. Open the DMG and drag **Lumo** into **Applications**.
3. Open **Lumo** from Applications.

## First launch: "Open Anyway" (one time)

Lumo is free and isn't signed with a paid Apple Developer certificate, so the first time you open it macOS says it can't verify the developer. To allow it:

1. Try to open Lumo once and click **Done** on the warning.
2. Open **System Settings → Privacy & Security**.
3. Scroll down to **Security**. You'll see *"Lumo" was blocked…* → click **Open Anyway**.
4. Confirm with **Open** (and your password or Touch ID if asked).

From then on Lumo opens normally.

<details>
<summary>Prefer Terminal?</summary>

```bash
xattr -dr com.apple.quarantine /Applications/Lumo.app
open /Applications/Lumo.app
```
</details>

## Updating

Download the new DMG and drag Lumo into Applications again (replace). Your library, likes and playlists are kept. You may need **Open Anyway** once more after an update.

## Using Lumo on Mac

| Action | Shortcut |
|---|---|
| Play / Pause | Space (or the keyboard's media keys) |
| Next / Previous | ⌘→ / ⌘← |
| Repeat | ⌘R |
| Volume up / down | ⌘↑ / ⌘↓ |
| Settings | ⌘, |

- The **player bar** at the bottom has like 👍/👎, **Lyrics** and **Up Next** (they open a side panel), AirPlay and volume.
- Lumo shows up in Control Center's **Now Playing**, so media keys and AirPods controls work.
