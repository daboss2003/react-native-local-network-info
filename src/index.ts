import { EventSubscription } from 'expo-modules-core';

import LocalNetworkInfoModule from './LocalNetworkInfoModule';
import { LocalIpInfo, NetworkInterfaceInfo } from './LocalNetworkInfo.types';

/**
 * Capture the device's current local-IP / network-role snapshot.
 *
 * Resolves with WiFi-station info when connected to WiFi; otherwise hotspot-host
 * info when the device is sharing its connection; otherwise `role: 'none'`.
 *
 * @example
 * ```ts
 * const info = await getLocalIp();
 * if (info.role === 'wifi') console.log('On WiFi at', info.ip);
 * if (info.role === 'hotspot') console.log('Hotspot host at', info.ip);
 * ```
 */
export async function getLocalIp(): Promise<LocalIpInfo> {
  return LocalNetworkInfoModule.getLocalIpAsync();
}

/**
 * Enumerate every up, non-loopback IPv4 interface with a best-effort role
 * classification. Useful for debugging odd devices/vendors.
 */
export async function getAllInterfaces(): Promise<NetworkInterfaceInfo[]> {
  return LocalNetworkInfoModule.getAllInterfacesAsync();
}

/**
 * Subscribe to network changes. The listener fires with a fresh
 * {@link LocalIpInfo} whenever connectivity, WiFi, or hotspot state changes.
 *
 * Remember to call `.remove()` on the returned subscription to avoid leaks
 * (or use {@link useLocalIp}, which manages this for you).
 *
 * @example
 * ```ts
 * const sub = addNetworkChangeListener((info) => {
 *   console.log('network changed →', info.role, info.ip);
 * });
 * // later
 * sub.remove();
 * ```
 */
export function addNetworkChangeListener(
  listener: (info: LocalIpInfo) => void
): EventSubscription {
  return LocalNetworkInfoModule.addListener('onNetworkChange', listener);
}

/** The raw native module instance (advanced use). */
export { default as LocalNetworkInfoModule } from './LocalNetworkInfoModule';

export { useLocalIp } from './useLocalIp';
export * from './LocalNetworkInfo.types';
