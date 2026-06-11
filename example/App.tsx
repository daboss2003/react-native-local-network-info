// This example shows every export of `local-network-info`:
//   • useLocalIp()              — the recommended hook; drives the live headline below.
//   • getAllInterfaces()        — imperative one-shot, powers the interface list.
//   • addNetworkChangeListener  — raw listener, powers the "change events" counter.
//   • getLocalIp()              — the one-shot equivalent of the hook (see refresh handler).
import {
  addNetworkChangeListener,
  getAllInterfaces,
  getLocalIp,
  useLocalIp,
  type LocalIpInfo,
  type NetworkInterfaceInfo,
} from 'react-native-local-network-info';
import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';

const roleColors: Record<LocalIpInfo['role'], string> = {
  wifi: '#1f9d55',
  hotspot: '#d97706',
  none: '#9ca3af',
};

const roleLabels: Record<LocalIpInfo['role'], string> = {
  wifi: '📶  Connected to WiFi',
  hotspot: '📡  Acting as Hotspot',
  none: '🚫  No WiFi / Hotspot',
};

export default function App() {
  // ① Recommended usage: the hook keeps `info` live automatically (initial
  //    snapshot on mount + updates on every network change), and cleans up
  //    its subscription on unmount. This single line is all most apps need.
  const info = useLocalIp();

  // ② The hook covers the headline; these imperative APIs power the extras.
  const [interfaces, setInterfaces] = useState<NetworkInterfaceInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [changeCount, setChangeCount] = useState(0);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      // getLocalIp() is the one-shot equivalent of the hook — shown here for
      // completeness; the displayed value comes from useLocalIp() above.
      const [, allInterfaces] = await Promise.all([getLocalIp(), getAllInterfaces()]);
      setInterfaces(allInterfaces);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();

    // The hook already listens internally; this extra listener just demonstrates
    // the raw API — counting changes and refreshing the interface list each time.
    const subscription = addNetworkChangeListener(() => {
      setChangeCount((count) => count + 1);
      getAllInterfaces().then(setInterfaces).catch(() => {});
    });

    return () => subscription.remove();
  }, [refresh]);

  return (
    <View style={styles.container}>
      <StatusBar style="auto" />
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={loading} onRefresh={refresh} />}>
        <Text style={styles.title}>Local Network Info</Text>
        <Text style={styles.subtitle}>headline below is powered by useLocalIp()</Text>

        <View style={[styles.banner, { backgroundColor: roleColors[info?.role ?? 'none'] }]}>
          <Text style={styles.bannerText}>{roleLabels[info?.role ?? 'none']}</Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.ip}>{info?.ip ?? '—'}</Text>
          <Text style={styles.ipCaption}>device local IPv4</Text>
        </View>

        <View style={styles.card}>
          <Row label="Role" value={info?.role ?? '—'} />
          <Row label="WiFi connected" value={yesNo(info?.isWifiConnected)} />
          <Row label="Hotspot host" value={yesNo(info?.isHotspotHost)} />
          <Row label="Interface" value={info?.interfaceName ?? '—'} />
          <Row label="Netmask" value={info?.netmask ?? '—'} />
          <Row label="Gateway" value={info?.gateway ?? '—'} />
          <Row
            label="Predicted clients"
            value={
              info?.predictedClientRange
                ? `${info.predictedClientRange.first} – ${info.predictedClientRange.last}`
                : '—'
            }
          />
          <Row label="Platform" value={info?.platform ?? '—'} />
          <Row label="Change events" value={String(changeCount)} />
        </View>

        <TouchableOpacity style={styles.button} onPress={refresh} disabled={loading}>
          {loading ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <Text style={styles.buttonText}>Refresh interfaces</Text>
          )}
        </TouchableOpacity>

        <Text style={styles.sectionTitle}>All interfaces</Text>
        {interfaces.length === 0 ? (
          <Text style={styles.muted}>No IPv4 interfaces found.</Text>
        ) : (
          interfaces.map((iface) => (
            <View key={`${iface.name}-${iface.ip}`} style={styles.ifaceRow}>
              <Text style={styles.ifaceName}>{iface.name}</Text>
              <Text style={styles.ifaceIp}>{iface.ip}</Text>
              <Text style={styles.ifaceRole}>{iface.role}</Text>
            </View>
          ))
        )}

        <Text style={styles.hint}>
          Toggle WiFi or your hotspot in Settings and watch the headline update live (no refresh
          needed) — that's the useLocalIp() hook reacting to change events.
        </Text>
      </ScrollView>
    </View>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

function yesNo(value: boolean | undefined): string {
  if (value === undefined) return '—';
  return value ? 'Yes' : 'No';
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0f172a' },
  content: { padding: 20, paddingTop: 64, paddingBottom: 48 },
  title: { fontSize: 26, fontWeight: '700', color: '#f8fafc' },
  subtitle: { fontSize: 13, color: '#64748b', marginBottom: 16 },
  banner: { borderRadius: 12, paddingVertical: 14, paddingHorizontal: 16, marginBottom: 16 },
  bannerText: { color: '#fff', fontSize: 16, fontWeight: '600', textAlign: 'center' },
  card: { backgroundColor: '#1e293b', borderRadius: 12, padding: 16, marginBottom: 16 },
  ip: { fontSize: 34, fontWeight: '800', color: '#f8fafc', textAlign: 'center', fontVariant: ['tabular-nums'] },
  ipCaption: { color: '#94a3b8', textAlign: 'center', marginTop: 4 },
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 6 },
  rowLabel: { color: '#94a3b8', fontSize: 14 },
  rowValue: { color: '#e2e8f0', fontSize: 14, fontWeight: '600' },
  button: {
    backgroundColor: '#2563eb',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
    marginBottom: 24,
  },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  sectionTitle: { color: '#f8fafc', fontSize: 18, fontWeight: '700', marginBottom: 8 },
  muted: { color: '#64748b' },
  ifaceRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#334155',
  },
  ifaceName: { color: '#e2e8f0', flex: 1, fontWeight: '600' },
  ifaceIp: { color: '#cbd5e1', flex: 1.4, fontVariant: ['tabular-nums'] },
  ifaceRole: { color: '#94a3b8', flex: 1, textAlign: 'right' },
  hint: { color: '#64748b', marginTop: 20, fontSize: 13, lineHeight: 18 },
});
