/**
 * Which network role is currently providing the device's local IPv4.
 *
 * - `wifi`    — the device is connected to a WiFi network as a station/client.
 * - `hotspot` — the device is acting as a WiFi hotspot (SoftAP) host.
 * - `none`    — neither WiFi nor hotspot is providing a usable LAN address
 *               (e.g. cellular-only, airplane mode, or offline).
 *
 * When both WiFi and hotspot are active simultaneously, `wifi` takes precedence.
 */
export type NetworkRole = 'wifi' | 'hotspot' | 'none';

/** The platform that produced a given snapshot. */
export type DevicePlatform = 'ios' | 'android' | 'web';

/** A predicted inclusive range of client IPv4 addresses on the hotspot subnet. */
export interface ClientIpRange {
  /** First usable client IPv4 (the host itself is excluded). */
  first: string;
  /** Last usable client IPv4. */
  last: string;
}

/**
 * A point-in-time snapshot of the device's local network position.
 *
 * The same shape is returned by {@link getLocalIp} and delivered to
 * {@link addNetworkChangeListener} / {@link useLocalIp}.
 */
export interface LocalIpInfo {
  /**
   * The device's own local IPv4 on the active LAN interface, or `null` when
   * there is no WiFi/hotspot LAN address. Follows the precedence:
   * WiFi station IP first, then hotspot host IP.
   */
  ip: string | null;

  /** Which role is providing {@link ip}. WiFi takes precedence over hotspot. */
  role: NetworkRole;

  /** `true` when connected to a WiFi network as a client/station. */
  isWifiConnected: boolean;

  /** `true` when this device is currently acting as a WiFi hotspot (SoftAP) host. */
  isHotspotHost: boolean;

  /**
   * Name of the network interface backing {@link ip}
   * (e.g. `en0`, `wlan0`, `bridge100`, `ap0`), or `null`.
   */
  interfaceName: string | null;

  /** Dotted IPv4 subnet mask for {@link ip} (e.g. `255.255.255.0`), or `null`. */
  netmask: string | null;

  /**
   * The LAN gateway.
   *
   * - As a **hotspot host**, this is the device itself (it *is* the gateway).
   * - As an **Android station**, this is the real default-route gateway
   *   (read from `LinkProperties`).
   * - As an **iOS station**, this is a best-effort guess derived from the
   *   subnet (the first host address), because Apple exposes no public gateway
   *   API. Treat the iOS station gateway as a heuristic.
   */
  gateway: string | null;

  /**
   * When this device is a hotspot host, the predicted inclusive range of IPs
   * its clients can receive, derived from the host IP + subnet:
   *
   * - **iOS** — the fixed Personal Hotspot range `172.20.10.2`–`172.20.10.14`.
   * - **Android** — computed from the (randomized on Android 11+) SoftAP subnet.
   *
   * `null` when the device is not a hotspot host.
   */
  predictedClientRange: ClientIpRange | null;

  /** Platform that captured this snapshot. */
  platform: DevicePlatform;

  /** Epoch milliseconds when this snapshot was captured natively. */
  timestamp: number;
}

/** Per-interface detail, returned by {@link getAllInterfaces} for debugging. */
export interface NetworkInterfaceInfo {
  /** Interface name (e.g. `en0`, `wlan0`, `bridge100`, `ap0`, `pdp_ip0`). */
  name: string;
  /** The interface's IPv4 address. */
  ip: string;
  /** Dotted IPv4 subnet mask, when known. */
  netmask: string | null;
  /** Best-effort role classification of the interface. */
  role: 'wifi' | 'hotspot' | 'cellular' | 'ethernet' | 'other';
}

/** Native event map for the module. */
export type LocalNetworkInfoModuleEvents = {
  /** Fired whenever the device's network position changes. */
  onNetworkChange: (info: LocalIpInfo) => void;
};
