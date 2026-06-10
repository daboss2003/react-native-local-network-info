import { NativeModule, requireNativeModule } from 'expo';

import {
  LocalNetworkInfoModuleEvents,
  LocalIpInfo,
  NetworkInterfaceInfo,
} from './LocalNetworkInfo.types';

declare class LocalNetworkInfoModule extends NativeModule<LocalNetworkInfoModuleEvents> {
  /** Capture the current local-IP/network-role snapshot. */
  getLocalIpAsync(): Promise<LocalIpInfo>;
  /** Enumerate all up, non-loopback IPv4 interfaces (for debugging/inspection). */
  getAllInterfacesAsync(): Promise<NetworkInterfaceInfo[]>;
}

// The string must match `Name("LocalNetworkInfo")` in the Swift/Kotlin modules.
export default requireNativeModule<LocalNetworkInfoModule>('LocalNetworkInfo');
