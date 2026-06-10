import { registerWebModule, NativeModule } from 'expo';

import {
  LocalNetworkInfoModuleEvents,
  LocalIpInfo,
  NetworkInterfaceInfo,
} from './LocalNetworkInfo.types';

/**
 * Web fallback. Browsers do not expose the device's local LAN IPv4, so this
 * reports `role: 'none'` with a `null` IP. It exists so the package imports
 * cleanly on web (Metro resolves `*.web.ts`) and the API surface stays stable.
 */
class LocalNetworkInfoModule extends NativeModule<LocalNetworkInfoModuleEvents> {
  async getLocalIpAsync(): Promise<LocalIpInfo> {
    return {
      ip: null,
      role: 'none',
      isWifiConnected: false,
      isHotspotHost: false,
      interfaceName: null,
      netmask: null,
      gateway: null,
      predictedClientRange: null,
      platform: 'web',
      timestamp: Date.now(),
    };
  }

  async getAllInterfacesAsync(): Promise<NetworkInterfaceInfo[]> {
    return [];
  }
}

export default registerWebModule(LocalNetworkInfoModule, 'LocalNetworkInfoModule');
