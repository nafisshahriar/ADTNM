/*
 * DtnmaSafetyShield.java
 * Formal RFC 9675 Safety Shield enforcing deterministic buffer, energy,
 * storm-guard, and critical-rule invariants.
 * Combines Pre-Action Safety Masking, Post-Action Override Fallback,
 * and Declarative Rule Sanitization. All bounds are configurable via DtnmaConfig.
 */
package routing;

import java.util.Arrays;
import java.util.List;

public class DtnmaSafetyShield {

    public static class ShieldDecision {
        private final int proposedAction;
        private final int executedAction;
        private final boolean intervened;
        private final String triggerAri;
        private final String explanation;
        private final boolean[] safeMask;

        public ShieldDecision(int proposedAction, int executedAction, boolean intervened,
                              String triggerAri, String explanation, boolean[] safeMask) {
            this.proposedAction = proposedAction;
            this.executedAction = executedAction;
            this.intervened = intervened;
            this.triggerAri = triggerAri;
            this.explanation = explanation;
            this.safeMask = safeMask;
        }

        public int getProposedAction() { return proposedAction; }
        public int getExecutedAction() { return executedAction; }
        public boolean isIntervened() { return intervened; }
        public String getTriggerAri() { return triggerAri; }
        public String getExplanation() { return explanation; }
        public boolean[] getSafeMask() { return safeMask; }
    }

    private final DtnmaConfig cfg;
    private long totalDecisions = 0;
    private long totalInterventions = 0;

    public DtnmaSafetyShield() {
        this(new DtnmaConfig());
    }

    public DtnmaSafetyShield(DtnmaConfig config) {
        this.cfg = (config != null) ? config : new DtnmaConfig();
    }

    /**
     * Sanitizes user-authored declarative rules at load time so invalid quotas,
     * intervals, or ingress modes cannot destabilize the router.
     */
    public static void sanitizeRules(List<StateBasedRule> rules) {
        if (rules == null) return;
        for (StateBasedRule rule : rules) {
            if (rule != null && rule.getActions() != null) {
                for (ControlAction action : rule.getActions()) {
                    if (action != null) {
                        action.sanitize();
                    }
                }
            }
        }
    }

    /**
     * Computes the admissible subset of policy profiles A_safe(s_t) satisfying
     * RFC 9675 buffer, energy, storm-guard, and critical-rule invariants.
     * Liveness Guarantee: action 0 (SAFE_EMERGENCY_HOLD) is ALWAYS admissible.
     */
    public boolean[] computeSafeActionMask(EddTelemetrySampler sampler,
                                           List<StateBasedRule> rules,
                                           StringBuilder reasonOut) {
        boolean[] mask = new boolean[DtnmaPolicyProfile.NUM_ACTIONS];
        Arrays.fill(mask, true);

        if (sampler == null) {
            if (reasonOut != null) reasonOut.append("SAMPLER_NULL_PASS");
            return mask;
        }

        double q = sampler.getEddValue(EddTelemetrySampler.EDD_QUEUE_RATIO);
        double critQ = sampler.getEddValue(EddTelemetrySampler.EDD_CRIT_QUEUE_RATIO);
        double drift = sampler.getEddValue(EddTelemetrySampler.EDD_QUEUE_DRIFT);
        double density = sampler.getEddValue(EddTelemetrySampler.EDD_NODE_DENSITY);
        double soc = sampler.getEddValue(EddTelemetrySampler.EDD_ENERGY_SOC);

        String primaryAri = "ARI://DTNMA/SHIELD/NOMINAL_PASS";
        boolean constrained = false;

        // Invariant S1: Buffer Criticality & Active Surge Protection
        if (critQ >= cfg.shieldCriticalCritQueue || (q >= cfg.shieldCriticalQueue && drift >= cfg.shieldSurgeDrift)) {
            restrictToMaxIndex(mask, 0); // Only {0}
            primaryAri = "ARI://DTNMA/SHIELD/BUF_CRITICAL_94";
            if (reasonOut != null) {
                reasonOut.append(String.format("CritQ=%.2f,Q=%.2f,dQ=%.3f ", critQ, q, drift));
            }
            constrained = true;
        } else if (critQ >= cfg.shieldWarnCritQueue || (q >= cfg.shieldSurgeQueue && drift > cfg.shieldSurgeDrift)) {
            restrictToMaxIndex(mask, 2); // {0, 1, 2}
            primaryAri = "ARI://DTNMA/SHIELD/BUF_CONGESTION_SURGE";
            if (reasonOut != null) reasonOut.append(String.format("CritQ=%.2f,Q=%.2f,dQ=%.3f ", critQ, q, drift));
            constrained = true;
        }

        // Invariant S2: Energy Survival Failsafe
        if (soc < cfg.shieldEnergyMin) {
            restrictToMaxIndex(mask, 1); // Only {0, 1}
            if (!constrained) {
                primaryAri = "ARI://DTNMA/SHIELD/ENERGY_CRITICAL";
            }
            if (reasonOut != null) reasonOut.append(String.format("SoC=%.2f<%.2f ", soc, cfg.shieldEnergyMin));
            constrained = true;
        }

        // Invariant S3: Dense VDTN Broadcast Storm Guard (only under actual buffer pressure)
        if (q >= cfg.shieldStormQueue && density >= cfg.shieldStormDensity) {
            mask[6] = false;
            mask[7] = false;
            if (!constrained) {
                primaryAri = "ARI://DTNMA/SHIELD/DENSE_STORM_GUARD";
            }
            if (reasonOut != null) reasonOut.append(String.format("Q=%.2f,Dens=%.1f ", q, density));
            constrained = true;
        }

        // Invariant S4: Declarative RFC 9675 Critical Safety Rules (Priority >= 100)
        if (rules != null) {
            for (StateBasedRule rule : rules) {
                if (rule.getPriority() >= 100 && rule.isTriggered(sampler)) {
                    int maxQuota = Integer.MAX_VALUE;
                    int minIngress = 0;
                    for (ControlAction ca : rule.getActions()) {
                        if (ca.getType() == ControlAction.ControlType.SET_SPRAY_QUOTA) {
                            maxQuota = Math.min(maxQuota, ca.getIntParam());
                        } else if (ca.getType() == ControlAction.ControlType.SET_INGRESS_MODE) {
                            minIngress = Math.max(minIngress, ca.getIntParam());
                        }
                    }
                    for (int i = 1; i < DtnmaPolicyProfile.NUM_ACTIONS; i++) {
                        DtnmaPolicyProfile p = DtnmaPolicyProfile.get(i);
                        if (p.getSprayQuota() > maxQuota || p.getIngressMode() < minIngress) {
                            mask[i] = false;
                        }
                    }
                    if (!constrained) {
                        primaryAri = rule.getAri();
                    }
                    if (reasonOut != null) reasonOut.append("SRL[").append(rule.getAri()).append("] ");
                    constrained = true;
                    break;
                }
            }
        }

        // Liveness Invariant: Profile 0 (SAFE_EMERGENCY_HOLD) is always allowed
        mask[0] = true;

        if (!constrained && reasonOut != null) {
            reasonOut.append(primaryAri);
        }
        return mask;
    }

    public static boolean isConstrained(boolean[] mask) {
        if (mask == null) return false;
        for (boolean b : mask) {
            if (!b) return true;
        }
        return false;
    }

    public ShieldDecision enforce(int proposedAction, int preferredSafeAction,
                                  EddTelemetrySampler sampler, List<StateBasedRule> rules) {
        int clampedProposal = Math.max(0, Math.min(DtnmaPolicyProfile.NUM_ACTIONS - 1, proposedAction));
        StringBuilder reasonBuf = new StringBuilder();
        boolean[] safeMask = computeSafeActionMask(sampler, rules, reasonBuf);

        boolean intervened = false;
        int executed = clampedProposal;
        String triggerAri = "ARI://DTNMA/SHIELD/PASS";

        if (!safeMask[clampedProposal]) {
            intervened = true;
            triggerAri = extractPrimaryAri(sampler, rules);
            if (preferredSafeAction >= 0 && preferredSafeAction < DtnmaPolicyProfile.NUM_ACTIONS
                    && safeMask[preferredSafeAction]) {
                executed = preferredSafeAction;
            } else {
                executed = selectMonotonicSafeFallback(clampedProposal, safeMask);
            }
        }

        totalDecisions++;
        if (intervened) {
            totalInterventions++;
        }

        String expl = intervened
                ? "SHIELD_OVERRIDE(" + DtnmaPolicyProfile.get(clampedProposal).getName()
                  + "->" + DtnmaPolicyProfile.get(executed).getName() + "): " + reasonBuf.toString().trim()
                : "SHIELD_PASS(" + DtnmaPolicyProfile.get(executed).getName() + ")";

        return new ShieldDecision(clampedProposal, executed, intervened, triggerAri, expl, safeMask);
    }

    private int selectMonotonicSafeFallback(int proposed, boolean[] safeMask) {
        for (int i = proposed; i >= 0; i--) {
            if (safeMask[i]) return i;
        }
        return 0;
    }

    private void restrictToMaxIndex(boolean[] mask, int maxAllowedIdx) {
        for (int i = maxAllowedIdx + 1; i < mask.length; i++) {
            mask[i] = false;
        }
    }

    private String extractPrimaryAri(EddTelemetrySampler sampler, List<StateBasedRule> rules) {
        if (sampler == null) return "ARI://DTNMA/SHIELD/SAFE_FALLBACK";
        double q = sampler.getEddValue(EddTelemetrySampler.EDD_QUEUE_RATIO);
        double critQ = sampler.getEddValue(EddTelemetrySampler.EDD_CRIT_QUEUE_RATIO);
        double drift = sampler.getEddValue(EddTelemetrySampler.EDD_QUEUE_DRIFT);
        double density = sampler.getEddValue(EddTelemetrySampler.EDD_NODE_DENSITY);
        double soc = sampler.getEddValue(EddTelemetrySampler.EDD_ENERGY_SOC);

        if (critQ >= cfg.shieldCriticalCritQueue || (q >= cfg.shieldCriticalQueue && drift >= cfg.shieldSurgeDrift)) {
            return "ARI://DTNMA/SHIELD/BUF_CRITICAL_94";
        }
        if (critQ >= cfg.shieldWarnCritQueue || (q >= cfg.shieldSurgeQueue && drift > cfg.shieldSurgeDrift)) {
            return "ARI://DTNMA/SHIELD/BUF_CONGESTION_SURGE";
        }
        if (soc < cfg.shieldEnergyMin) return "ARI://DTNMA/SHIELD/ENERGY_CRITICAL";
        if (q >= cfg.shieldStormQueue && density >= cfg.shieldStormDensity) return "ARI://DTNMA/SHIELD/DENSE_STORM_GUARD";
        if (rules != null) {
            for (StateBasedRule r : rules) {
                if (r.getPriority() >= 100 && r.isTriggered(sampler)) {
                    return r.getAri();
                }
            }
        }
        return "ARI://DTNMA/SHIELD/SAFE_GUARD";
    }

    public long getTotalDecisions() { return totalDecisions; }
    public long getTotalInterventions() { return totalInterventions; }

    public double getInterventionRate() {
        return totalDecisions > 0 ? (double) totalInterventions / totalDecisions : 0.0;
    }
}
