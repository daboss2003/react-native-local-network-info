# react-native-local-network-info

Read a React Native device's **own local IPv4 address** and whether it's **connected to WiFi** or **acting as a hotspot host** — with **live network-change events**.

> ℹ️ This library only reads **your own device's** network interfaces. It does **not** scan, probe, or connect to any other device on the network, and it needs no location permission.

Built with the [Expo Modules API](https://docs.expo.dev/modules/overview/), so it works in **both bare React Native and Expo** apps and supports the New Architecture out of the box.

## Why

`expo-network` / `WifiManager.getConnectionInfo()` only return the **WiFi station** IP — they report `0.0.0.0` when the device is the hotspot host. This module reads the device's network interfaces directly, so it returns a usable LAN IP whether the device is a WiFi client **or** the hotspot, and tells you which.

## Features

- ✅ Device's own **local IPv4** on the active LAN interface.
- ✅ **Role**: `wifi`, `hotspot`, or `none`.
- ✅ **WiFi takes precedence** when both WiFi and hotspot are active at once.
- ✅ **Hotspot host** detection that handles Android 11+'s **randomized** SoftAP subnet (reads the real interface IP — never hardcodes `192.168.43.1`).
- ✅ **Predicted client IP range** when the device is a hotspot (iOS: fixed `172.20.10.2–.14`; Android: derived from the live subnet).
- ✅ **Live listener** that re-fires on every connectivity / WiFi / hotspot change.
- ✅ A `useLocalIp()` React hook.
- ✅ No location permission required.

## Installation

```sh
npm install react-native-local-network-info
```

> **Bare React Native:** you must also have the `expo` package installed so Expo Modules autolinking works. If you don't yet:
> ```sh
> npx install-expo-modules@latest
> ```
> Then `cd ios && pod install`. Expo apps need no extra steps.

### Requirements

| | Minimum |
| --- | --- |
| Expo SDK | 54+ (New Architecture) |
| iOS | 15.1 |
| Android | API 24 (Android 7.0) |

### Permissions

Android permissions are merged automatically via the module's manifest:

- `ACCESS_NETWORK_STATE` — for the change listener & station gateway.
- `ACCESS_WIFI_STATE` — for the DHCP gateway fallback.

No `ACCESS_FINE_LOCATION` and no iOS `Info.plist` keys are needed — the module only reads its **own** interfaces (it never connects to or scans other devices).

## Usage

```ts
import {
  getLocalIp,
  addNetworkChangeListener,
  useLocalIp,
} from 'react-native-local-network-info';

// One-shot read
const info = await getLocalIp();
console.log(info.ip, info.role); // e.g. "192.168.1.42" "wifi"

// Live updates
const sub = addNetworkChangeListener((info) => {
  console.log('network changed →', info.role, info.ip);
});
// later: sub.remove();
```

### React hook

```tsx
import { useLocalIp } from 'react-native-local-network-info';

function Status() {
  const info = useLocalIp(); // null until first snapshot, then live
  if (!info) return <Text>Detecting…</Text>;
  return (
    <Text>
      {info.role === 'hotspot' ? 'Hosting hotspot at' : 'Local IP'}: {info.ip}
    </Text>
  );
}
```

## API

### `getLocalIp(): Promise<LocalIpInfo>`

Captures the current snapshot.

### `addNetworkChangeListener(cb): EventSubscription`

Subscribes to changes. Call `.remove()` on the returned subscription to unsubscribe.

### `getAllInterfaces(): Promise<NetworkInterfaceInfo[]>`

Every up, non-loopback IPv4 interface with a best-effort role — handy for debugging unusual devices.

### `useLocalIp(): LocalIpInfo | null`

Hook returning the latest snapshot, kept live and auto-cleaned on unmount.

### `LocalIpInfo`

```ts
interface LocalIpInfo {
  ip: string | null;                 // device's own local IPv4 (wifi first, then hotspot)
  role: 'wifi' | 'hotspot' | 'none'; // wifi wins when both are active
  isWifiConnected: boolean;
  isHotspotHost: boolean;
  interfaceName: string | null;      // "en0" | "wlan0" | "bridge100" | "ap0" | ...
  netmask: string | null;            // "255.255.255.0"
  gateway: string | null;            // see notes below
  predictedClientRange: { first: string; last: string } | null; // hotspot only
  platform: 'ios' | 'android' | 'web';
  timestamp: number;                 // epoch ms
}
```

## How detection works

| | iOS | Android |
| --- | --- | --- |
| Enumeration | `getifaddrs(3)` | `java.net.NetworkInterface` |
| WiFi station | `en0` | the interface `ConnectivityManager` reports for the `TRANSPORT_WIFI` network (usually `wlan0`) |
| Hotspot host | `bridge*` @ `172.20.10.1/28` | any WiFi-family interface (`wlan*`/`ap*`/`swlan*`/`softap*`) that is **not** the confirmed station — reads its live IP |
| Cellular (ignored for LAN IP) | `pdp_ip0` | `rmnet*` / `ccmni*` |
| Change events | `NWPathMonitor` | `registerDefaultNetworkCallback` + `WIFI_AP_STATE_CHANGED` |
| Station gateway | derived from subnet (heuristic) | `LinkProperties` default route (real) |

**Precedence:** if a WiFi-station IP exists it is returned with `role: 'wifi'`; otherwise the hotspot-host IP is returned with `role: 'hotspot'`; otherwise `role: 'none'` with `ip: null`.

## Platform notes & limitations

- **iOS station gateway is a heuristic** (first host of the subnet). Apple exposes no public default-gateway API; the WiFi IP, netmask, and role are accurate.
- **iOS hotspot change events:** `NWPathMonitor` reliably reports WiFi/cellular changes, but toggling Personal Hotspot while the default path is unchanged may not fire an event. Call `getLocalIp()` to force a fresh read when you need certainty.
- **Android hotspot vs. station** is resolved by asking `ConnectivityManager` which interface carries the real `TRANSPORT_WIFI` (station) network, then treating any *other* WiFi-family interface with an IP as the hotspot. This correctly handles phones that host the SoftAP on `wlan0` itself (not just `ap0`/`swlan0`), and devices running WiFi + hotspot concurrently. Use `getAllInterfaces()` to inspect an unusual device.
- **Web** returns `role: 'none'` / `ip: null` — browsers don't expose the LAN IP.
- iOS interface-name → role mapping is a documented **heuristic** (Apple exposes no public role API); the hotspot-host `172.20.10.1`/`255.255.255.240` fingerprint and `NetworkInterface` reads are the reliable signals.

## Example app

```sh
cd example
npm install
npx expo prebuild        # generates ios/ and android/
npx expo run:ios         # or: npx expo run:android
```

Then toggle WiFi / your hotspot in Settings and watch the values update live.

## License

MIT
