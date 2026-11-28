/*
 * DtnmaConfig.java
 * Centralized, user-tunable configuration for Mobility-Aware Autonomic DTNMA.
 *
 * Loading precedence:
 *   1. Built-in defaults
 *   2. External plain-text file via DtnmaRouter.configFile / Group.configFile
 *   3. Inline DtnmaRouter.<key> scenario settings
 *   4. Per-group Group.<key> overrides
 */
package routing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import core.Settings;

public class DtnmaConfig {

    public static final String CONFIG_FILE_SETTING = "configFile";

    public enum ManagementMode {
        M0_STATIC,
        M1_REMOTE_ONLY,
        M2_AUTONOMIC_SRL,
        M2_MOBILITY_AWARE
    }

    public enum InterventionTier {
        TIER1_PROTECTION_ONLY,
        TIER2_PROTECTION_INGRESS_ENERGY,
        TIER3_FULL_WITH_REPLICA_CAPS
    }

    // Management & Control-Plane Toggles
    public ManagementMode managementMode = ManagementMode.M2_MOBILITY_AWARE;
    public InterventionTier interventionTier = InterventionTier.TIER3_FULL_WITH_REPLICA_CAPS;
    public int staticQuota = 6;
    public int staticIngressMode = 0;
    public boolean staticBackpressure = true;
    public boolean enablePriorityProtection = true;
    public boolean densityAdaptiveBeacons = true;
    public boolean ackFeedbackEnabled = true;

    // Contact Beacon & Delayed ACK Control Plane
    public double beaconRefreshInterval = 5.0;
    public int ackCacheSize = 512;
    public int ackMaxPerContact = 32;

    // RFC 9675 Safety Shield Thresholds
    public double shieldCriticalQueue = 0.94;
    public double shieldCongestionQueue = 0.90;
    public double shieldCriticalCritQueue = 0.50;
    public double shieldWarnCritQueue = 0.35;
    public double shieldSurgeQueue = 0.82;
    public double shieldSurgeDrift = 0.015;
    public double shieldStormQueue = 0.78;
    public double shieldStormDensity = 5.0;
    public double shieldEnergyMin = 0.20;

    // Mobility-Aware Resource Controller Parameters (M2_MOBILITY_AWARE)
    public double mobHorizonSec = 0.0;
    public double mobTtdWarnRatio = 1.10;
    public double mobTtdCritRatio = 0.60;
    public double mobShortContactSec = 12.0;
    public double mobLongIctSec = 180.0;
    public String mobAblationMode = "JOINT";

    public DtnmaConfig() {}

    public DtnmaConfig(DtnmaConfig o) {
        if (o == null) return;
        this.managementMode = o.managementMode;
        this.interventionTier = o.interventionTier;
        this.staticQuota = o.staticQuota;
        this.staticIngressMode = o.staticIngressMode;
        this.staticBackpressure = o.staticBackpressure;
        this.enablePriorityProtection = o.enablePriorityProtection;
        this.densityAdaptiveBeacons = o.densityAdaptiveBeacons;
        this.ackFeedbackEnabled = o.ackFeedbackEnabled;

        this.beaconRefreshInterval = o.beaconRefreshInterval;
        this.ackCacheSize = o.ackCacheSize;
        this.ackMaxPerContact = o.ackMaxPerContact;

        this.shieldCriticalQueue = o.shieldCriticalQueue;
        this.shieldCongestionQueue = o.shieldCongestionQueue;
        this.shieldCriticalCritQueue = o.shieldCriticalCritQueue;
        this.shieldWarnCritQueue = o.shieldWarnCritQueue;
        this.shieldSurgeQueue = o.shieldSurgeQueue;
        this.shieldSurgeDrift = o.shieldSurgeDrift;
        this.shieldStormQueue = o.shieldStormQueue;
        this.shieldStormDensity = o.shieldStormDensity;
        this.shieldEnergyMin = o.shieldEnergyMin;

        this.mobHorizonSec = o.mobHorizonSec;
        this.mobTtdWarnRatio = o.mobTtdWarnRatio;
        this.mobTtdCritRatio = o.mobTtdCritRatio;
        this.mobShortContactSec = o.mobShortContactSec;
        this.mobLongIctSec = o.mobLongIctSec;
        this.mobAblationMode = o.mobAblationMode;
    }

    /**
     * Loads configuration from defaults -> configFile -> DtnmaRouter namespace -> Group overrides.
     */
    public static DtnmaConfig load(Settings groupSettings, Settings dtnmaSettings) {
        DtnmaConfig cfg = new DtnmaConfig();

        String cfgFile = null;
        if (groupSettings != null && groupSettings.contains(CONFIG_FILE_SETTING)) {
            cfgFile = groupSettings.getSetting(CONFIG_FILE_SETTING);
        } else if (dtnmaSettings != null && dtnmaSettings.contains(CONFIG_FILE_SETTING)) {
            cfgFile = dtnmaSettings.getSetting(CONFIG_FILE_SETTING);
        }

        if (cfgFile != null && !cfgFile.trim().isEmpty()) {
            try {
                Map<String, String> fileParams = parseKeyValueFile(cfgFile.trim());
                cfg.applyStringMap(fileParams);
            } catch (IOException e) {
                System.err.println("Warning: Failed to load DTNMA configFile '" + cfgFile
                        + "' (" + e.getMessage() + "), using built-in defaults.");
            }
        }

        // Apply namespace-level then group-level ONE Settings overrides
        if (dtnmaSettings != null) cfg.applySettings(dtnmaSettings);
        if (groupSettings != null) cfg.applySettings(groupSettings);

        cfg.clampSafeBounds();
        return cfg;
    }

    private static Map<String, String> parseKeyValueFile(String filePath) throws IOException {
        File f = new File(filePath);
        if (!f.exists()) {
            File alt1 = new File("the-one", filePath);
            if (alt1.exists()) {
                f = alt1;
            } else {
                File alt2 = new File("..", filePath);
                if (alt2.exists()) {
                    f = alt2;
                } else {
                    throw new IOException("Config file not found: " + filePath);
                }
            }
        }

        Map<String, String> map = new HashMap<String, String>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                int hashIdx = line.indexOf('#');
                if (hashIdx >= 0) {
                    line = line.substring(0, hashIdx);
                }
                line = line.trim();
                if (line.isEmpty()) continue;

                int eqIdx = line.indexOf('=');
                if (eqIdx <= 0) continue;

                String key = line.substring(0, eqIdx).trim();
                if (key.startsWith(DtnmaRouter.DTNMA_NS + ".")) {
                    key = key.substring(DtnmaRouter.DTNMA_NS.length() + 1);
                }
                String val = line.substring(eqIdx + 1).trim();
                if (!key.isEmpty() && !val.isEmpty()) {
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    private void applyStringMap(Map<String, String> params) {
        if (params.containsKey("managementMode")) {
            managementMode = parseManagementMode(params.get("managementMode"), managementMode);
        }
        if (params.containsKey("interventionTier")) {
            interventionTier = parseInterventionTier(params.get("interventionTier"), interventionTier);
        }
        staticQuota = parseInt(params, "staticQuota", staticQuota);
        staticIngressMode = parseInt(params, "staticIngressMode", staticIngressMode);
        staticBackpressure = parseBool(params, "staticBackpressure", staticBackpressure);
        enablePriorityProtection = parseBool(params, "enablePriorityProtection", enablePriorityProtection);
        densityAdaptiveBeacons = parseBool(params, "densityAdaptiveBeacons", densityAdaptiveBeacons);
        densityAdaptiveBeacons = parseBool(params, "densityAdaptiveGossip", densityAdaptiveBeacons);
        ackFeedbackEnabled = parseBool(params, "ackFeedbackEnabled", ackFeedbackEnabled);

        beaconRefreshInterval = parseDouble(params, "beaconRefreshInterval", beaconRefreshInterval);
        ackCacheSize = parseInt(params, "ackCacheSize", ackCacheSize);
        ackMaxPerContact = parseInt(params, "ackMaxPerContact", ackMaxPerContact);

        shieldCriticalQueue = parseDouble(params, "shieldCriticalQueue", shieldCriticalQueue);
        shieldCongestionQueue = parseDouble(params, "shieldCongestionQueue", shieldCongestionQueue);
        shieldCriticalCritQueue = parseDouble(params, "shieldCriticalCritQueue", shieldCriticalCritQueue);
        shieldWarnCritQueue = parseDouble(params, "shieldWarnCritQueue", shieldWarnCritQueue);
        shieldSurgeQueue = parseDouble(params, "shieldSurgeQueue", shieldSurgeQueue);
        shieldSurgeDrift = parseDouble(params, "shieldSurgeDrift", shieldSurgeDrift);
        shieldStormQueue = parseDouble(params, "shieldStormQueue", shieldStormQueue);
        shieldStormDensity = parseDouble(params, "shieldStormDensity", shieldStormDensity);
        shieldEnergyMin = parseDouble(params, "shieldEnergyMin", shieldEnergyMin);

        mobHorizonSec = parseDouble(params, "mobHorizonSec", mobHorizonSec);
        mobTtdWarnRatio = parseDouble(params, "mobTtdWarnRatio", mobTtdWarnRatio);
        mobTtdCritRatio = parseDouble(params, "mobTtdCritRatio", mobTtdCritRatio);
        mobShortContactSec = parseDouble(params, "mobShortContactSec", mobShortContactSec);
        mobLongIctSec = parseDouble(params, "mobLongIctSec", mobLongIctSec);
        if (params.containsKey("mobAblationMode")) {
            mobAblationMode = params.get("mobAblationMode").trim().toUpperCase();
        }
    }

    private void applySettings(Settings s) {
        if (s.contains("managementMode")) {
            managementMode = parseManagementMode(s.getSetting("managementMode"), managementMode);
        }
        if (s.contains("interventionTier")) {
            interventionTier = parseInterventionTier(s.getSetting("interventionTier"), interventionTier);
        }
        if (s.contains("staticQuota")) staticQuota = s.getInt("staticQuota");
        if (s.contains("staticIngressMode")) staticIngressMode = s.getInt("staticIngressMode");
        if (s.contains("staticBackpressure")) staticBackpressure = s.getBoolean("staticBackpressure");
        if (s.contains("enablePriorityProtection")) enablePriorityProtection = s.getBoolean("enablePriorityProtection");
        if (s.contains("densityAdaptiveGossip")) densityAdaptiveBeacons = s.getBoolean("densityAdaptiveGossip");
        if (s.contains("densityAdaptiveBeacons")) densityAdaptiveBeacons = s.getBoolean("densityAdaptiveBeacons");
        if (s.contains("ackFeedbackEnabled")) ackFeedbackEnabled = s.getBoolean("ackFeedbackEnabled");

        if (s.contains("beaconRefreshInterval")) beaconRefreshInterval = s.getDouble("beaconRefreshInterval");
        if (s.contains("ackCacheSize")) ackCacheSize = s.getInt("ackCacheSize");
        if (s.contains("ackMaxPerContact")) ackMaxPerContact = s.getInt("ackMaxPerContact");

        if (s.contains("shieldCriticalQueue")) shieldCriticalQueue = s.getDouble("shieldCriticalQueue");
        if (s.contains("shieldCongestionQueue")) shieldCongestionQueue = s.getDouble("shieldCongestionQueue");
        if (s.contains("shieldCriticalCritQueue")) shieldCriticalCritQueue = s.getDouble("shieldCriticalCritQueue");
        if (s.contains("shieldWarnCritQueue")) shieldWarnCritQueue = s.getDouble("shieldWarnCritQueue");
        if (s.contains("shieldSurgeQueue")) shieldSurgeQueue = s.getDouble("shieldSurgeQueue");
        if (s.contains("shieldSurgeDrift")) shieldSurgeDrift = s.getDouble("shieldSurgeDrift");
        if (s.contains("shieldStormQueue")) shieldStormQueue = s.getDouble("shieldStormQueue");
        if (s.contains("shieldStormDensity")) shieldStormDensity = s.getDouble("shieldStormDensity");
        if (s.contains("shieldEnergyMin")) shieldEnergyMin = s.getDouble("shieldEnergyMin");

        if (s.contains("mobHorizonSec")) mobHorizonSec = s.getDouble("mobHorizonSec");
        if (s.contains("mobTtdWarnRatio")) mobTtdWarnRatio = s.getDouble("mobTtdWarnRatio");
        if (s.contains("mobTtdCritRatio")) mobTtdCritRatio = s.getDouble("mobTtdCritRatio");
        if (s.contains("mobShortContactSec")) mobShortContactSec = s.getDouble("mobShortContactSec");
        if (s.contains("mobLongIctSec")) mobLongIctSec = s.getDouble("mobLongIctSec");
        if (s.contains("mobAblationMode")) mobAblationMode = s.getSetting("mobAblationMode").trim().toUpperCase();
    }

    public void clampSafeBounds() {
        staticQuota = Math.max(1, Math.min(32, staticQuota));
        staticIngressMode = Math.max(0, Math.min(2, staticIngressMode));
        beaconRefreshInterval = Math.max(0.5, beaconRefreshInterval);
        ackCacheSize = Math.max(32, Math.min(10000, ackCacheSize));
        ackMaxPerContact = Math.max(1, Math.min(512, ackMaxPerContact));

        shieldCriticalQueue = Math.max(0.50, Math.min(1.0, shieldCriticalQueue));
        shieldCongestionQueue = Math.max(0.40, Math.min(shieldCriticalQueue, shieldCongestionQueue));
        shieldCriticalCritQueue = Math.max(0.10, Math.min(1.0, shieldCriticalCritQueue));
        shieldWarnCritQueue = Math.max(0.05, Math.min(shieldCriticalCritQueue, shieldWarnCritQueue));
        shieldSurgeQueue = Math.max(0.30, Math.min(shieldCongestionQueue, shieldSurgeQueue));
        shieldSurgeDrift = Math.max(0.001, Math.min(0.50, shieldSurgeDrift));
        shieldStormQueue = Math.max(0.30, Math.min(shieldCongestionQueue, shieldStormQueue));
        shieldStormDensity = Math.max(1.0, shieldStormDensity);
        shieldEnergyMin = Math.max(0.0, Math.min(0.90, shieldEnergyMin));

        mobHorizonSec = Math.max(0.0, mobHorizonSec);
        mobTtdCritRatio = Math.max(0.10, Math.min(2.0, mobTtdCritRatio));
        mobTtdWarnRatio = Math.max(mobTtdCritRatio, Math.min(3.0, mobTtdWarnRatio));
        mobShortContactSec = Math.max(1.0, Math.min(300.0, mobShortContactSec));
        mobLongIctSec = Math.max(10.0, Math.min(7200.0, mobLongIctSec));
        if (mobAblationMode == null || mobAblationMode.trim().isEmpty()) {
            mobAblationMode = "JOINT";
        } else {
            mobAblationMode = mobAblationMode.trim().toUpperCase();
        }
    }

    public ManagementMode getEffectiveManagementMode() {
        return (managementMode != null) ? managementMode : ManagementMode.M2_MOBILITY_AWARE;
    }

    private static ManagementMode parseManagementMode(String val, ManagementMode def) {
        if (val == null) return def;
        String norm = val.trim().toUpperCase();
        if ("AUTO".equals(norm)) return ManagementMode.M2_MOBILITY_AWARE;
        try {
            return ManagementMode.valueOf(norm);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static InterventionTier parseInterventionTier(String val, InterventionTier def) {
        if (val == null) return def;
        String norm = val.trim().toUpperCase();
        if ("1".equals(norm) || "TIER1".equals(norm)) return InterventionTier.TIER1_PROTECTION_ONLY;
        if ("2".equals(norm) || "TIER2".equals(norm)) return InterventionTier.TIER2_PROTECTION_INGRESS_ENERGY;
        if ("3".equals(norm) || "TIER3".equals(norm)) return InterventionTier.TIER3_FULL_WITH_REPLICA_CAPS;
        try {
            return InterventionTier.valueOf(norm);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static double parseDouble(Map<String, String> m, String k, double def) {
        String v = m.get(k);
        if (v == null) return def;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
    }

    private static int parseInt(Map<String, String> m, String k, int def) {
        String v = m.get(k);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static boolean parseBool(Map<String, String> m, String k, boolean def) {
        String v = m.get(k);
        if (v == null) return def;
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) return true;
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return false;
        return def;
    }
}
