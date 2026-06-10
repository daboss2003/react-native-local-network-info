import { useEffect, useState } from 'react';

import LocalNetworkInfoModule from './LocalNetworkInfoModule';
import { LocalIpInfo } from './LocalNetworkInfo.types';

/**
 * React hook that returns the latest {@link LocalIpInfo} and keeps it updated as
 * the network changes.
 *
 * It captures an initial snapshot on mount and then subscribes to native
 * `onNetworkChange` events, cleaning up the subscription on unmount.
 *
 * @returns the latest snapshot, or `null` until the first snapshot resolves.
 *
 * @example
 * ```tsx
 * const info = useLocalIp();
 * return <Text>{info?.ip ?? 'detecting…'} ({info?.role})</Text>;
 * ```
 */
export function useLocalIp(): LocalIpInfo | null {
  const [info, setInfo] = useState<LocalIpInfo | null>(null);

  useEffect(() => {
    let mounted = true;

    LocalNetworkInfoModule.getLocalIpAsync()
      .then((snapshot) => {
        if (mounted) {
          setInfo(snapshot);
        }
      })
      .catch(() => {
        // Swallow — a change event or a later manual refresh will populate it.
      });

    const subscription = LocalNetworkInfoModule.addListener('onNetworkChange', (snapshot) => {
      if (mounted) {
        setInfo(snapshot);
      }
    });

    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);

  return info;
}
