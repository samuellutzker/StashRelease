---
title: Stash Privacy Policy
description: Privacy policy for the Stash app
---

# Stash — Privacy Policy

_Last updated: 2026-06-14_

Stash is a self-hosted file sync app. You run the server on your own hardware, and the app syncs your content only to that server. There is no developer-operated cloud behind Stash.

## The short version

- Stash does **not** collect any data about you. The developer (and whoever publishes Stash) receives nothing.
- Everything you save stays on your device and on the server **you** run.
- The app talks only to your own server. There is no relay, no developer backend, no third-party service in the path.
- There is **no advertising, no analytics, and no crash reporting** sent to the developer or anyone else.
- Stash is open source — you can read exactly what it does.

## What data Stash handles

Stash stores the content you choose to save: links, text notes, images, videos, audio, documents, archives, contacts (vCards), and other files you share into it. This content lives:

1. On your device (in the app's private storage), and
2. On the sync server you operate, if you enable sync.

That's the whole picture. The data never passes through any machine the developer controls, because no such machine exists in the system.

## Where data goes on the network

When sync is enabled, your device connects **directly** to the server address you configure (a hostname/IP and port on your LAN, or your own DynDNS/VPN endpoint). Network traffic flows only between your device and your own server. Nothing is sent to the developer or any third party.

### Encryption in transit

- By default, connections use **TLS** (`wss://` — WebSocket over TLS).
- The server auto-generates a **self-signed certificate**. On the first connection, the app records ("pins") the certificate's SHA-256 fingerprint. If the certificate ever changes afterward, the app refuses to connect — this is trust-on-first-use and protects against man-in-the-middle interception.
- Plain, unencrypted `ws://` is available only if you deliberately turn TLS off (intended for a trusted LAN). It is not the default.
- Your password is never sent or stored in plaintext. The app transmits only `SHA-256(password)`; the server stores `Argon2id(SHA-256(password))`.

### QR code pairing

To set up a client quickly, the server can show a QR code. That QR code contains only:

- the server host,
- the server port, and
- the TLS certificate fingerprint.

It contains no personal data. The password is never embedded in the QR code; you enter it manually after scanning.

## Android permissions

Stash requests the following permissions, and only for the reasons given:

| Permission | Why Stash needs it |
|---|---|
| `INTERNET` | To open the network connection from your device to your own sync server. This is the only network access Stash uses, and it goes only to the address you configure. |
| `READ_EXTERNAL_STORAGE` (Android 12 / API 32 and below only) | To read a file you share into Stash on older Android versions, before the granular media permissions existed. |
| `READ_MEDIA_IMAGES` | To read an image you share into Stash on Android 13+ (replaces the broad storage permission for images). |
| `READ_MEDIA_VIDEO` | To read a video you share into Stash on Android 13+. |
| `READ_MEDIA_AUDIO` | To read an audio file you share into Stash on Android 13+. |
| `FOREGROUND_SERVICE` | To run sync as a foreground service so transfers can continue reliably and visibly while you switch apps. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ requires this specific service type to declare that the foreground service is performing data synchronization (the background sync worker). |
| `POST_NOTIFICATIONS` | To show the sync progress / status notification. On Android 13+ this requires your explicit permission. |

Stash requests no location, contacts-access, microphone, camera-for-tracking, account, or advertising-identifier permissions. (Contacts you save are vCard files you explicitly share in — Stash does not read your address book.)

## No tracking, no third parties

- No analytics SDKs.
- No advertising or ad identifiers.
- No crash/telemetry reporting to the developer.
- No third-party libraries that phone home.

The app contains no developer-side data collection of any kind.

## Your control over your data

Because the server is yours, you are in full control:

- Delete individual items from within the app (locally, or everywhere via your server).
- Wipe the server's data directory directly to remove everything at once.

There is no developer account to delete because there is no developer account in the first place.

## Children's privacy

Stash collects no data from anyone, including children. It has no accounts and no developer backend.

## Changes to this policy

If this policy changes, the updated version will be published in the app's repository with a new "Last updated" date.

## Contact

Questions about privacy in Stash:

**samuellutzker@web.de**

Stash is open source; you are welcome to inspect the code to verify any statement above.
