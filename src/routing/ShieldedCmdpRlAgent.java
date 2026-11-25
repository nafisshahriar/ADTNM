/*
 * ShieldedCmdpRlAgent.java
 * Single-Critic Shielded Counterfactual Linear Expected SARSA with State-Localized
 * Counterfactual Shaping and Opportunistic Mobility-Class Peer Gossip Consensus.
 * Retires the slack primal-dual cost-critic / lambda loop in favor of direct
 * S1-S4 invariant masking and hazard-localized counterfactual penalty shaping.
 * All hyperparameters are externally configurable via DtnmaConfig.
 */
package routing;

import java.util.Random;

public class ShieldedCmdpRlAgent {

    public static final int NUM_ACTIONS = DtnmaPolicyProfile.NUM_ACTIONS;
    public static final int NUM_FEATURES = 20;

    /**
     * Hazard-localized feature indices updated during counterfactual shield shaping:
     * phi[6]=energyDepletion(1-soc) [S2],
     * phi[15]=(Q>=shieldStormQueue && Q<shieldCongestionQueue && Density>=shieldStormDensity) [S3],
     * phi[16]=(Q>=shieldCongestionQueue) [S1/S4],
     * phi[17]=(Q>=0.80 && drift>0.55) [S1 surge].
     */
    private static final int[] HAZARD_FEATURE_INDICES = { 6, 15, 16, 17 };

    private final DtnmaConfig cfg;
    private final double[][] wReward;
    private final double lambda = 0.0; // Retired slack multiplier (kept as 0.0 for log/API compatibility)
    private final Random rng;

    // Tracking & Diagnostic Audit Counters
    private long epochCount = 0;
    private double rewardSum = 0.0;
    private double costSum = 0.0;
    private double lastReward = 0.0;
    private double lastCost = 0.0;

    public ShieldedCmdpRlAgent(long rngSeed, int hostAddress) {
        this(rngSeed, hostAddress, new DtnmaConfig());
    }

    public ShieldedCmdpRlAgent(long rngSeed, int hostAddress, DtnmaConfig config) {
        this.cfg = (config != null) ? config : new DtnmaConfig();
        this.wReward = new double[NUM_ACTIONS][NUM_FEATURES];
        // Uniform Optimistic Initial Values on bias weight only (zero prior bias toward any profile)
        for (int a = 0; a < NUM_ACTIONS; a++) {
            this.wReward[a][0] = this.cfg.rlOptimisticInit;
        }
        long seed = rngSeed * 131L + (long) hostAddress * 31L + 17L;
        this.rng = new Random(seed);
    }

    /**
     * Extracts 20-dimensional normalized basis feature vector phi(s) from
     * 6-dimensional EDD state z in [0, 1]^6.
     */
    public double[] extractFeatures(double[] z) {
        double[] phi = new double[NUM_FEATURES];
        double z0 = z.length > 0 ? z[0] : 0.0; // queueRatio
        double z1 = z.length > 1 ? z[1] : 0.5; // queueDriftNorm
        double z2 = z.length > 2 ? z[2] : 0.0; // densityNorm
        double z3 = z.length > 3 ? z[3] : 0.0; // speedNorm
        double z4 = z.length > 4 ? z[4] : 0.5; // neighborGradNorm
        double z5 = z.length > 5 ? z[5] : 1.0; // energySoc

        phi[0] = 1.0;
        phi[1] = z0;
        phi[2] = z1;
        phi[3] = z2;
        phi[4] = z3;
        phi[5] = z4;
        phi[6] = 1.0 - z5; // Energy depletion (0.0 at full battery; rises during low-SoC occultation)

        // Quadratic & cross-subsystem interaction terms
        phi[7]  = z0 * z0;
        phi[8]  = z0 * z1;
        phi[9]  = z0 * z2;
        phi[10] = z2 * z3;
        phi[11] = z0 * z4;

        // Localized coarse-tile indicators over (Q, Density, Drift, Speed)
        phi[12] = (z0 < 0.50 && z2 < 0.35) ? 1.0 : 0.0;
        phi[13] = (z0 < 0.50 && z2 >= 0.35) ? 1.0 : 0.0;
        double stormDensNorm = (cfg.shieldStormDensity / 15.0) - 1e-6;
        phi[14] = (z0 >= 0.50 && z0 < cfg.shieldCongestionQueue && z2 < 0.35) ? 1.0 : 0.0;
        phi[15] = (z0 >= cfg.shieldStormQueue && z0 < cfg.shieldCongestionQueue && z2 >= stormDensNorm) ? 1.0 : 0.0;
        phi[16] = (z0 >= cfg.shieldCongestionQueue && z1 >= 0.55) ? 1.0 : 0.0;
        phi[17] = (z0 >= 0.80 && z1 > 0.55) ? 1.0 : 0.0;
        phi[18] = (z3 < 0.15) ? 1.0 : 0.0;
        phi[19] = (z3 >= 0.50) ? 1.0 : 0.0;

        return phi;
    }

    private double dot(double[] weights, double[] phi) {
        double sum = 0.0;
        for (int k = 0; k < NUM_FEATURES; k++) {
            sum += weights[k] * phi[k];
        }
        return sum;
    }

    private double normSq(double[] phi) {
        double sum = 0.0;
        for (int k = 0; k < NUM_FEATURES; k++) {
            sum += phi[k] * phi[k];
        }
        return Math.max(1.0, sum);
    }

    private double normSqHazard(double[] phi) {
        double sum = 0.0;
        for (int idx : HAZARD_FEATURE_INDICES) {
            sum += phi[idx] * phi[idx];
        }
        return Math.max(0.5, sum);
    }

    public double getEpsilon(double simTime) {
        return Math.max(cfg.rlEpsilonMin, cfg.rlEpsilonStart * Math.exp(-simTime / cfg.rlEpsilonDecay));
    }

    /**
     * Returns deterministic (epsilon = 0) greedy action value over all 0..7 profiles.
     */
    public int proposeGreedyAction(double[] stateVec) {
        double[] phi = extractFeatures(stateVec);
        return selectGreedyAction(phi, null);
    }

    /**
     * Returns single-critic Expected SARSA state-action value Q(s, a).
     * Method name retained for backward API compatibility.
     */
    public double getLagrangianValue(double[] stateVec, int action) {
        int a = Math.max(0, Math.min(NUM_ACTIONS - 1, action));
        double[] phi = extractFeatures(stateVec);
        return dot(wReward[a], phi);
    }

    /**
     * Proposes an unconstrained RL action over all 0..7 profiles using decaying
     * epsilon-greedy on Q(s, a).
     */
    public int proposeRawAction(double[] stateVec, double simTime) {
        double eps = getEpsilon(simTime);
        if (rng.nextDouble() < eps) {
            return rng.nextInt(NUM_ACTIONS);
        }
        double[] phi = extractFeatures(stateVec);
        return selectGreedyAction(phi, null);
    }

    /**
     * Selects the highest-value safe action restricted to safeMask.
     */
    public int selectBestSafeAction(double[] stateVec, boolean[] safeMask, double simTime, boolean allowExplore) {
        double eps = allowExplore ? getEpsilon(simTime) : 0.0;
        if (allowExplore && rng.nextDouble() < eps) {
            int safeCount = 0;
            for (int a = 0; a < NUM_ACTIONS; a++) {
                if (safeMask == null || safeMask[a]) safeCount++;
            }
            if (safeCount > 0) {
                int pick = rng.nextInt(safeCount);
                int idx = 0;
                for (int a = 0; a < NUM_ACTIONS; a++) {
                    if (safeMask == null || safeMask[a]) {
                        if (idx == pick) return a;
                        idx++;
                    }
                }
            }
        }
        double[] phi = extractFeatures(stateVec);
        return selectGreedyAction(phi, safeMask);
    }

    private int selectGreedyAction(double[] phi, boolean[] mask) {
        double bestVal = Double.NEGATIVE_INFINITY;
        int bestAct = 0;
        int ties = 0;

        for (int a = 0; a < NUM_ACTIONS; a++) {
            if (mask != null && !mask[a]) continue;
            double qVal = dot(wReward[a], phi);

            if (qVal > bestVal + 1e-9) {
                bestVal = qVal;
                bestAct = a;
                ties = 1;
            } else if (Math.abs(qVal - bestVal) <= 1e-9) {
                ties++;
                if (rng.nextInt(ties) == 0) {
                    bestAct = a;
                }
            }
        }
        return bestAct;
    }

    /**
     * Safe-Anchored Counterfactual Shielded Learning penalty:
     * Anchors the counterfactual target strictly below the executed safe action's actual
     * Q(s, a_safe) with margin, driving Q(s, unsafe) < Q(s, safe) while updating ONLY
     * hazard-localized state features so healthy uncongested regimes remain unaffected.
     */
    public void applyShieldPenaltyUpdate(double[] stateVec, int unsafeAction, int safeAction) {
        if (unsafeAction < 0 || unsafeAction >= NUM_ACTIONS) return;
        int safeIdx = Math.max(0, Math.min(NUM_ACTIONS - 1, safeAction));
        double[] phi = extractFeatures(stateVec);
        double step = (3.0 * cfg.rlAlpha) / normSqHazard(phi);

        double qSafe = dot(wReward[safeIdx], phi);
        double targetR = Math.min(-1.5, qSafe - 1.5);
        double curR = dot(wReward[unsafeAction], phi);
        double errR = clamp(targetR - curR, -12.0, 12.0);

        for (int k : HAZARD_FEATURE_INDICES) {
            double updated = (1.0 - cfg.rlWeightDecay) * wReward[unsafeAction][k] + step * errR * phi[k];
            wReward[unsafeAction][k] = clamp(updated, -20.0, 20.0);
        }
    }

    /**
     * Causal Delayed-ACK Eligibility Credit: credits the specific (forwardState, forwardAction)
     * pair responsible for forwarding a bundle whose delivery ACK later returns.
     */
    public void applyDelayedAckCredit(double[] forwardStateVec, int forwardAction, double ackReward) {
        if (forwardStateVec == null || forwardAction < 0 || forwardAction >= NUM_ACTIONS) return;
        double[] phi = extractFeatures(forwardStateVec);
        double step = (0.5 * cfg.rlAlpha) / normSq(phi);
        double err = clamp(ackReward, 0.0, 3.0);
        for (int k = 0; k < NUM_FEATURES; k++) {
            wReward[forwardAction][k] = clamp(wReward[forwardAction][k] + step * err * phi[k], -20.0, 20.0);
        }
    }

    /**
     * Single-Critic Expected SARSA TD update over executed transition (s, a_exec, r, s').
     * Retains cost audit counters (costSum, lastCost) for diagnostic logging only.
     */
    public void updateTransition(double[] prevState, int executedAction, double reward, double cost,
                                 double[] nextState, boolean[] nextSafeMask, boolean useMaskForNext,
                                 double simTime) {
        if (prevState == null || nextState == null) return;
        if (executedAction < 0 || executedAction >= NUM_ACTIONS) return;

        double[] phiPrev = extractFeatures(prevState);
        double[] phiNext = extractFeatures(nextState);
        boolean[] evalMask = useMaskForNext ? nextSafeMask : null;

        int validCount = 0;
        for (int a = 0; a < NUM_ACTIONS; a++) {
            if (evalMask == null || evalMask[a]) validCount++;
        }
        if (validCount == 0) validCount = 1;

        int greedyNext = selectGreedyAction(phiNext, evalMask);
        double epsNext = getEpsilon(simTime);

        double expectedNextR = 0.0;
        for (int a = 0; a < NUM_ACTIONS; a++) {
            if (evalMask != null && !evalMask[a]) continue;
            double prob = epsNext / validCount;
            if (a == greedyNext) {
                prob += (1.0 - epsNext);
            }
            expectedNextR += prob * dot(wReward[a], phiNext);
        }

        double targetR = reward + cfg.rlGamma * expectedNextR;
        double predR = dot(wReward[executedAction], phiPrev);
        double deltaR = clamp(targetR - predR, -5.0, 5.0);

        double step = cfg.rlAlpha / normSq(phiPrev);
        for (int k = 0; k < NUM_FEATURES; k++) {
            double updated = (1.0 - cfg.rlWeightDecay) * wReward[executedAction][k] + step * deltaR * phiPrev[k];
            wReward[executedAction][k] = clamp(updated, -20.0, 20.0);
        }

        this.epochCount++;
        this.lastReward = reward;
        this.lastCost = cost;
        this.rewardSum += reward;
        this.costSum += cost;
    }

    /**
     * Atomic, order-independent Same-Mobility-Class Gossip Consensus averaging over wReward.
     * Snapshots both agents before writing so merge outcomes are strictly symmetric
     * and immune to connection iteration order; guards against early herd-lock via gossipMinEpochs.
     */
    public static boolean gossipMergeSymmetric(ShieldedCmdpRlAgent a, ShieldedCmdpRlAgent b,
                                              int classA, int classB) {
        if (a == null || b == null) {
            return false;
        }
        if ((a.cfg.gossipSameClassOnly || b.cfg.gossipSameClassOnly) && classA != classB) {
            return false;
        }
        int minEpochs = Math.max(a.cfg.gossipMinEpochs, b.cfg.gossipMinEpochs);
        if (a.epochCount < minEpochs && b.epochCount < minEpochs) {
            return false;
        }

        double alphaA;
        if (b.epochCount < minEpochs) {
            alphaA = 0.0;
        } else if (a.epochCount < minEpochs) {
            alphaA = Math.min(0.15, a.cfg.gossipWeightMax);
        } else {
            double rawA = a.cfg.gossipWeightMax * b.epochCount / (a.epochCount + b.epochCount + 1.0);
            alphaA = clamp(rawA, a.cfg.gossipWeightMin, a.cfg.gossipWeightMax);
        }

        double alphaB;
        if (a.epochCount < minEpochs) {
            alphaB = 0.0;
        } else if (b.epochCount < minEpochs) {
            alphaB = Math.min(0.15, b.cfg.gossipWeightMax);
        } else {
            double rawB = b.cfg.gossipWeightMax * a.epochCount / (b.epochCount + a.epochCount + 1.0);
            alphaB = clamp(rawB, b.cfg.gossipWeightMin, b.cfg.gossipWeightMax);
        }

        if (alphaA <= 0.0 && alphaB <= 0.0) {
            return false;
        }

        double[][] snapAR = new double[NUM_ACTIONS][NUM_FEATURES];
        double[][] snapBR = new double[NUM_ACTIONS][NUM_FEATURES];
        for (int act = 0; act < NUM_ACTIONS; act++) {
            System.arraycopy(a.wReward[act], 0, snapAR[act], 0, NUM_FEATURES);
            System.arraycopy(b.wReward[act], 0, snapBR[act], 0, NUM_FEATURES);
        }

        if (alphaA > 0.0) {
            for (int act = 0; act < NUM_ACTIONS; act++) {
                for (int k = 0; k < NUM_FEATURES; k++) {
                    a.wReward[act][k] = (1.0 - alphaA) * snapAR[act][k] + alphaA * snapBR[act][k];
                }
            }
        }

        if (alphaB > 0.0) {
            for (int act = 0; act < NUM_ACTIONS; act++) {
                for (int k = 0; k < NUM_FEATURES; k++) {
                    b.wReward[act][k] = (1.0 - alphaB) * snapBR[act][k] + alphaB * snapAR[act][k];
                }
            }
        }

        return true;
    }

    /**
     * Single-sided opportunistic Mobility-Class Gossip Consensus averaging over wReward (backward compatibility).
     */
    public boolean gossipMerge(ShieldedCmdpRlAgent peer, int myClass, int peerClass) {
        if (peer == null || peer.epochCount < Math.max(this.cfg.gossipMinEpochs, peer.cfg.gossipMinEpochs)) {
            return false;
        }
        if (cfg.gossipSameClassOnly && myClass != peerClass) {
            return false;
        }

        double alphaG;
        if (this.epochCount < cfg.gossipMinEpochs) {
            alphaG = Math.min(0.15, cfg.gossipWeightMax);
        } else {
            double rawAlphaG = cfg.gossipWeightMax * peer.epochCount / (this.epochCount + peer.epochCount + 1.0);
            alphaG = clamp(rawAlphaG, cfg.gossipWeightMin, cfg.gossipWeightMax);
        }
        for (int a = 0; a < NUM_ACTIONS; a++) {
            for (int k = 0; k < NUM_FEATURES; k++) {
                this.wReward[a][k] = (1.0 - alphaG) * this.wReward[a][k] + alphaG * peer.wReward[a][k];
            }
        }
        return true;
    }

    public double getLambda() { return lambda; }
    public long getEpochCount() { return epochCount; }
    public double getLastReward() { return lastReward; }
    public double getLastCost() { return lastCost; }
    public double getAvgReward() { return epochCount > 0 ? rewardSum / epochCount : 0.0; }
    public double getAvgCost() { return epochCount > 0 ? costSum / epochCount : 0.0; }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
