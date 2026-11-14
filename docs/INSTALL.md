# Installing Nive on your iPhone

Nive is installed with **SideStore** and a **free Apple ID**. Apple signs free sideloaded apps for 7 days at a time; SideStore renews that signature for you, so you set this up once.

You need: an iPhone on iOS 26+, a Mac/PC/Linux computer (once), a USB cable, and an Apple ID.

## 1. One-time setup

1. **On the iPhone:** install **LocalDevVPN** from the App Store. SideStore uses it to talk to your phone without a computer.
2. **On the computer:** download **iloader** from the [SideStore docs](https://docs.sidestore.io/docs/installation/prerequisites) and open it.
3. Connect the iPhone with a cable. If it asks **Trust This Computer?**, tap **Trust** and enter your passcode.
4. In iloader, sign in with your Apple ID, select your iPhone and install **SideStore (Stable)**.
5. **On the iPhone:** go to **Settings → General → VPN & Device Management**, tap your Apple ID under *Developer App* and tap **Trust**.
6. Go to **Settings → Privacy & Security → Developer Mode**, turn it on, and let the iPhone restart.
7. Open **LocalDevVPN** and tap **Connect**, then open **SideStore** and sign in with the same Apple ID. If it asks for a pairing file, choose **On My iPhone → SideStore → ALTPairingFile.mobiledevicepairing**.

## 2. Install Nive

1. In SideStore, go to **Sources → +** and add:
   ```
   https://raw.githubusercontent.com/Nagashreeshyl/nive/main/altstore.json
   ```
2. Open the Nive source and tap **Get / Install**.
3. Open Nive from your home screen.

You can also download `Nive.ipa` from the [latest release](https://github.com/Nagashreeshyl/nive/releases/latest) and install it with SideStore (**My Apps → +**) or iloader (**Import IPA**).

## 3. Keeping it working

- **Updates** appear in SideStore as **Update**. Your library is kept.
- **Weekly refresh:** SideStore renews Nive in the background when LocalDevVPN is connected. To be safe, once a week connect LocalDevVPN, open SideStore and tap **Refresh All**. That takes about 10 seconds, with no computer.
- If you forget, Nive won't open until you refresh. Your songs and playlists are not lost.
- A free Apple ID allows **3 sideloaded apps** at a time, and SideStore counts as one.

## LocalDevVPN, briefly

It only handles traffic to a local address SideStore uses to reach your own phone. Your normal internet traffic doesn't go through it. Keeping it on is fine, but:
- iOS allows one VPN at a time, so turning on another VPN disconnects it.
- A few banking or streaming apps refuse to work while *any* VPN is on. Turn it off while using them.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Device management settings do not allow using apps from developer…" | Settings → General → VPN & Device Management → trust your Apple ID. |
| "The current pairing file is invalid" (often after an iOS update) | In iloader, use **Manage Pairing File → Place** next to SideStore. |
| Nive won't open | Connect LocalDevVPN → SideStore → **Refresh All**. |
| Can't find the `.ipa` after AirDrop | Use iloader's **Import IPA**, or Finder → your iPhone → Files tab → drag it onto SideStore. |
| Music stops or won't play | Check SideStore for a Nive update; YouTube changes occasionally require one. |
