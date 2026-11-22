/*
 * DtnmaConfig.java
 * Centralized, user-tunable hyperparameter & policy configuration for Autonomic DTNMA.
 * Defaults to Gossip + Delayed ACK Shielded CMDP RL (DtnmaGossipAckRL behavior).
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

    // Intelligence & Control-Plane Toggles (Default: Gossip + ACK Shielded CMDP RL)
    public DtnmaRouter.IntelligenceMode intelligenceMode = DtnmaRouter.IntelligenceMode.SHIELDED_CMDP_RL;
    public boolean gossipEnabled = true;
    public boolean ackFeedbackEnabled = true;
    public double rlEpochInterval = 15.0;

    // Single-Critic Expected SARSA RL Hyperparameters (rlLambdaInit/rlEtaLambda retained at 0.0 for config compatibility)
    public double rlAlpha = 0.15;
    public double rlGamma = 0.90;
    public double rlWeightDecay = 1e-4;
    public double rlEpsilonStart = 0.15;
    public double rlEpsilonMin = 0.01;
    public double rlEpsilonDecay = 900.0;
    public double rlLambdaInit = 0.0;
    public double rlEtaLambda = 0.0;
    public double rlTargetCost = 0.30;
    public double rlLambdaMax = 5.0;
    public double rlOptimisticInit = 0.0;

    // Local-Only Reward / Cost Weights
    public double rewardDirectDelivery = 2.5;
    public double rewardAckDelivery = 1.5;
    public double rewardPredProgress = 1.5;
    public double rewardCoverageGain = 1.0;
    public double penaltyBulkDrop = 0.35;
    public double penaltyRelayCost = 0.02;

    // Gossip, Beacon & ACK Control Plane
    public double gossipCooldown = 120.0;
    public double gossipWeightMin = 0.05;
    public double gossipWeightMax = 0.25;
    public boolean gossipSameClassOnly = true;
    public int gossipMinEpochs = 8;
    public double beaconRefreshInterval = 5.0;
    public int ackCacheSize = 512;
    public int ackMaxPerContact = 32;

    // RFC 9675 Safety Shield Thresholds
    public double shieldCriticalQueue = 0.94;
    public double shieldCongestionQueue = 0.90;
    public double shieldSurgeQueue = 0.82;
    public double shieldSurgeDrift = 0.015;
    public double shieldStormQueue = 0.78;
    public double shieldStormDensity = 5.0;
    public double shieldEnergyMin = 0.20;

    public DtnmaConfig() {}

    public DtnmaConfig(DtnmaConfig o) {
        if (o == null) return;
        this.intelligenceMode = o.intelligenceMode;
        this.gossipEnabled = o.gossipEnabled;
        this.ackFeedbackEnabled = o.ackFeedbackEnabled;
        this.rlEpochInterval = o.rlEpochInterval;

        this.rlAlpha = o.rlAlpha;
        this.rlGamma = o.rlGamma;
        this.rlWeightDecay = o.rlWeightDecay;
        this.rlEpsilonStart = o.rlEpsilonStart;
        this.rlEpsilonMin = o.rlEpsilonMin;
        this.rlEpsilonDecay = o.rlEpsilonDecay;
        this.rlLambdaInit = o.rlLambdaInit;
        this.rlEtaLambda = o.rlEtaLambda;
        this.rlTargetCost = o.rlTargetCost;
        this.rlLambdaMax = o.rlLambdaMax;
        this.rlOptimisticInit = o.rlOptimisticInit;

        this.rewardDirectDelivery = o.rewardDirectDelivery;
        this.rewardAckDelivery = o.rewardAckDelivery;
        this.rewardPredProgress = o.rewardPredProgress;
        this.rewardCoverageGain = o.rewardCoverageGain;
        this.penaltyBulkDrop = o.penaltyBulkDrop;
        this.penaltyRelayCost = o.penaltyRelayCost;

        this.gossipCooldown = o.gossipCooldown;
        this.gossipWeightMin = o.gossipWeightMin;
        this.gossipWeightMax = o.gossipWeightMax;
        this.gossipSameClassOnly = o.gossipSameClassOnly;
        this.gossipMinEpochs = o.gossipMinEpochs;
        this.beaconRefreshInterval = o.beaconRefreshInterval;
        this.ackCacheSize = o.ackCacheSize;
        this.ackMaxPerContact = o.ackMaxPerContact;

        this.shieldCriticalQueue = o.shieldCriticalQueue;
        this.shieldCongestionQueue = o.shieldCongestionQueue;
        this.shieldSurgeQueue = o.shieldSurgeQueue;
        this.shieldSurgeDrift = o.shieldSurgeDrift;
        this.shieldStormQueue = o.shieldStormQueue;
        this.shieldStormDensity = o.shieldStormDensity;
        this.shieldEnergyMin = o.shieldEnergyMin;
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
        if (params.containsKey("intelligenceMode")) {
            intelligenceMode = parseIntelligenceMode(params.get("intelligenceMode"), intelligenceMode);
        }
        gossipEnabled = parseBool(params, "gossipEnabled", gossipEnabled);
        ackFeedbackEnabled = parseBool(params, "ackFeedbackEnabled", ackFeedbackEnabled);
        rlEpochInterval = parseDouble(params, "rlEpochInterval", rlEpochInterval);

        rlAlpha = parseDouble(params, "rlAlpha", rlAlpha);
        rlGamma = parseDouble(params, "rlGamma", rlGamma);
        rlWeightDecay = parseDouble(params, "rlWeightDecay", rlWeightDecay);
        rlEpsilonStart = parseDouble(params, "rlEpsilonStart", rlEpsilonStart);
        rlEpsilonMin = parseDouble(params, "rlEpsilonMin", rlEpsilonMin);
        rlEpsilonDecay = parseDouble(params, "rlEpsilonDecay", rlEpsilonDecay);
        rlLambdaInit = parseDouble(params, "rlLambdaInit", rlLambdaInit);
        rlEtaLambda = parseDouble(params, "rlEtaLambda", rlEtaLambda);
        rlTargetCost = parseDouble(params, "rlTargetCost", rlTargetCost);
        rlLambdaMax = parseDouble(params, "rlLambdaMax", rlLambdaMax);
        rlOptimisticInit = parseDouble(params, "rlOptimisticInit", rlOptimisticInit);

        rewardDirectDelivery = parseDouble(params, "rewardDirectDelivery", rewardDirectDelivery);
        rewardAckDelivery = parseDouble(params, "rewardAckDelivery", rewardAckDelivery);
        rewardPredProgress = parseDouble(params, "rewardPredProgress", rewardPredProgress);
        rewardCoverageGain = parseDouble(params, "rewardCoverageGain", rewardCoverageGain);
        penaltyBulkDrop = parseDouble(params, "penaltyBulkDrop", penaltyBulkDrop);
        penaltyRelayCost = parseDouble(params, "penaltyRelayCost", penaltyRelayCost);

        gossipCooldown = parseDouble(params, "gossipCooldown", gossipCooldown);
        gossipWeightMin = parseDouble(params, "gossipWeightMin", gossipWeightMin);
        gossipWeightMax = parseDouble(params, "gossipWeightMax", gossipWeightMax);
        gossipSameClassOnly = parseBool(params, "gossipSameClassOnly", gossipSameClassOnly);
        gossipMinEpochs = parseInt(params, "gossipMinEpochs", gossipMinEpochs);
        beaconRefreshInterval = parseDouble(params, "beaconRefreshInterval", beaconRefreshInterval);
        ackCacheSize = parseInt(params, "ackCacheSize", ackCacheSize);
        ackMaxPerContact = parseInt(params, "ackMaxPerContact", ackMaxPerContact);

        shieldCriticalQueue = parseDouble(params, "shieldCriticalQueue", shieldCriticalQueue);
        shieldCongestionQueue = parseDouble(params, "shieldCongestionQueue", shieldCongestionQueue);
        shieldSurgeQueue = parseDouble(params, "shieldSurgeQueue", shieldSurgeQueue);
        shieldSurgeDrift = parseDouble(params, "shieldSurgeDrift", shieldSurgeDrift);
        shieldStormQueue = parseDouble(params, "shieldStormQueue", shieldStormQueue);
        shieldStormDensity = parseDouble(params, "shieldStormDensity", shieldStormDensity);
        shieldEnergyMin = parseDouble(params, "shieldEnergyMin", shieldEnergyMin);
    }

    private void applySettings(Settings s) {
        if (s.contains("intelligenceMode")) {
            intelligenceMode = parseIntelligenceMode(s.getSetting("intelligenceMode"), intelligenceMode);
        }
        if (s.contains("gossipEnabled")) gossipEnabled = s.getBoolean("gossipEnabled");
        if (s.contains("ackFeedbackEnabled")) ackFeedbackEnabled = s.getBoolean("ackFeedbackEnabled");
        if (s.contains("rlEpochInterval")) rlEpochInterval = s.getDouble("rlEpochInterval");

        if (s.contains("rlAlpha")) rlAlpha = s.getDouble("rlAlpha");
        if (s.contains("rlGamma")) rlGamma = s.getDouble("rlGamma");
        if (s.contains("rlWeightDecay")) rlWeightDecay = s.getDouble("rlWeightDecay");
        if (s.contains("rlEpsilonStart")) rlEpsilonStart = s.getDouble("rlEpsilonStart");
        if (s.contains("rlEpsilonMin")) rlEpsilonMin = s.getDouble("rlEpsilonMin");
        if (s.contains("rlEpsilonDecay")) rlEpsilonDecay = s.getDouble("rlEpsilonDecay");
        if (s.contains("rlLambdaInit")) rlLambdaInit = s.getDouble("rlLambdaInit");
        if (s.contains("rlEtaLambda")) rlEtaLambda = s.getDouble("rlEtaLambda");
        if (s.contains("rlTargetCost")) rlTargetCost = s.getDouble("rlTargetCost");
        if (s.contains("rlLambdaMax")) rlLambdaMax = s.getDouble("rlLambdaMax");
        if (s.contains("rlOptimisticInit")) rlOptimisticInit = s.getDouble("rlOptimisticInit");

        if (s.contains("rewardDirectDelivery")) rewardDirectDelivery = s.getDouble("rewardDirectDelivery");
        if (s.contains("rewardAckDelivery")) rewardAckDelivery = s.getDouble("rewardAckDelivery");
        if (s.contains("rewardPredProgress")) rewardPredProgress = s.getDouble("rewardPredProgress");
        if (s.contains("rewardCoverageGain")) rewardCoverageGain = s.getDouble("rewardCoverageGain");
        if (s.contains("penaltyBulkDrop")) penaltyBulkDrop = s.getDouble("penaltyBulkDrop");
        if (s.contains("penaltyRelayCost")) penaltyRelayCost = s.getDouble("penaltyRelayCost");

        if (s.contains("gossipCooldown")) gossipCooldown = s.getDouble("gossipCooldown");
        if (s.contains("gossipWeightMin")) gossipWeightMin = s.getDouble("gossipWeightMin");
        if (s.contains("gossipWeightMax")) gossipWeightMax = s.getDouble("gossipWeightMax");
        if (s.contains("gossipSameClassOnly")) gossipSameClassOnly = s.getBoolean("gossipSameClassOnly");
        if (s.contains("gossipMinEpochs")) gossipMinEpochs = s.getInt("gossipMinEpochs");
        if (s.contains("beaconRefreshInterval")) beaconRefreshInterval = s.getDouble("beaconRefreshInterval");
        if (s.contains("ackCacheSize")) ackCacheSize = s.getInt("ackCacheSize");
        if (s.contains("ackMaxPerContact")) ackMaxPerContact = s.getInt("ackMaxPerContact");

        if (s.contains("shieldCriticalQueue")) shieldCriticalQueue = s.getDouble("shieldCriticalQueue");
        if (s.contains("shieldCongestionQueue")) shieldCongestionQueue = s.getDouble("shieldCongestionQueue");
        if (s.contains("shieldSurgeQueue")) shieldSurgeQueue = s.getDouble("shieldSurgeQueue");
        if (s.contains("shieldSurgeDrift")) shieldSurgeDrift = s.getDouble("shieldSurgeDrift");
        if (s.contains("shieldStormQueue")) shieldStormQueue = s.getDouble("shieldStormQueue");
        if (s.contains("shieldStormDensity")) shieldStormDensity = s.getDouble("shieldStormDensity");
        if (s.contains("shieldEnergyMin")) shieldEnergyMin = s.getDouble("shieldEnergyMin");
    }

    public void clampSafeBounds() {
        rlEpochInterval = Math.max(1.0, Math.min(600.0, rlEpochInterval));
        rlAlpha = Math.max(1e-4, Math.min(1.0, rlAlpha));
        rlGamma = Math.max(0.0, Math.min(0.999, rlGamma));
        rlWeightDecay = Math.max(0.0, Math.min(0.1, rlWeightDecay));
        rlEpsilonStart = Math.max(0.0, Math.min(1.0, rlEpsilonStart));
        rlEpsilonMin = Math.max(0.0, Math.min(rlEpsilonStart, rlEpsilonMin));
        rlEpsilonDecay = Math.max(10.0, rlEpsilonDecay);
        rlLambdaMax = Math.max(0.1, Math.min(100.0, rlLambdaMax));
        rlLambdaInit = Math.max(0.0, Math.min(rlLambdaMax, rlLambdaInit));
        rlEtaLambda = Math.max(0.0, Math.min(1.0, rlEtaLambda));
        rlTargetCost = Math.max(0.0, Math.min(2.0, rlTargetCost));

        rewardDirectDelivery = Math.max(0.0, rewardDirectDelivery);
        rewardAckDelivery = Math.max(0.0, rewardAckDelivery);
        rewardPredProgress = Math.max(0.0, rewardPredProgress);
        rewardCoverageGain = Math.max(0.0, rewardCoverageGain);
        penaltyBulkDrop = Math.max(0.0, penaltyBulkDrop);
        penaltyRelayCost = Math.max(0.0, penaltyRelayCost);

        gossipCooldown = Math.max(1.0, gossipCooldown);
        gossipWeightMin = Math.max(0.0, Math.min(0.5, gossipWeightMin));
        gossipWeightMax = Math.max(gossipWeightMin, Math.min(0.5, gossipWeightMax));
        gossipMinEpochs = Math.max(1, Math.min(500, gossipMinEpochs));
        beaconRefreshInterval = Math.max(0.5, beaconRefreshInterval);
        ackCacheSize = Math.max(32, Math.min(10000, ackCacheSize));
        ackMaxPerContact = Math.max(1, Math.min(512, ackMaxPerContact));

        shieldCriticalQueue = Math.max(0.50, Math.min(1.0, shieldCriticalQueue));
        shieldCongestionQueue = Math.max(0.40, Math.min(shieldCriticalQueue, shieldCongestionQueue));
        shieldSurgeQueue = Math.max(0.30, Math.min(shieldCongestionQueue, shieldSurgeQueue));
        shieldSurgeDrift = Math.max(0.001, Math.min(0.50, shieldSurgeDrift));
        shieldStormQueue = Math.max(0.30, Math.min(shieldCongestionQueue, shieldStormQueue));
        shieldStormDensity = Math.max(1.0, shieldStormDensity);
        shieldEnergyMin = Math.max(0.0, Math.min(0.90, shieldEnergyMin));
    }

    private static DtnmaRouter.IntelligenceMode parseIntelligenceMode(String val, DtnmaRouter.IntelligenceMode def) {
        if (val == null) return def;
        try {
            return DtnmaRouter.IntelligenceMode.valueOf(val.trim().toUpperCase());
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
