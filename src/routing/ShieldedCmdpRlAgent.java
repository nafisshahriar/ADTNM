/*
 * ShieldedCmdpRlAgent.java
 * Primal-Dual Constrained Linear Expected SARSA with State-Localized Dual-Update
 * Shielded Learning and Opportunistic Mobility-Class Peer Gossip Consensus.
 * All hyperparameters are externally configurable via DtnmaConfig.
 */
package routing;

import java.util.Random;

public class ShieldedCmdpRlAgent {

    public static final int NUM_ACTIONS = DtnmaPolicyProfile.NUM_ACTIONS;
    public static final int NUM_FEATURES = 20;

    private final DtnmaConfig cfg;
    private final double[][] wReward;
    private final double[][] wCost;
    private double lambda;
    private final Random rng;

    // Tracking & Metrics
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
        this.lambda = this.cfg.rlLambdaInit;
        this.wReward = new double[NUM_ACTIONS][NUM_FEATURES];
        this.wCost = new double[NUM_ACTIONS][NUM_FEATURES];
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
        phi[6] = z5;

        // Quadratic & cross-subsystem interaction terms
        phi[7]  = z0 * z0;
        phi[8]  = z0 * z1;
        phi[9]  = z0 * z2;
        phi[10] = z2 * z3;
        phi[11] = z0 * z4;

        // Localized coarse-tile indicators over (Q, Density, Drift, Speed)
        phi[12] = (z0 < 0.50 && z2 < 0.35) ? 1.0 : 0.0;
        phi[13] = (z0 < 0.50 && z2 >= 0.35) ? 1.0 : 0.0;
        phi[14] = (z0 >= 0.50 && z0 < 0.85 && z2 < 0.35) ? 1.0 : 0.0;
        phi[15] = (z0 >= 0.50 && z0 < 0.85 && z2 >= 0.35) ? 1.0 : 0.0;
        phi[16] = (z0 >= 0.85) ? 1.0 : 0.0;
        phi[17] = (z1 > 0.55) ? 1.0 : 0.0;
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

    private double normSqLocalized(double[] phi) {
        double sum = 0.0;
        for (int k = 1; k < NUM_FEATURES; k++) {
            sum += phi[k] * phi[k];
        }
        return Math.max(1.0, sum);
    }

    public double getEpsilon(double simTime) {
        return Math.max(cfg.rlEpsilonMin, cfg.rlEpsilonStart * Math.exp(-simTime / cfg.rlEpsilonDecay));
    }

    /**
     * Proposes an unconstrained RL action over all 0..7 profiles using decaying
     * epsilon-greedy on Lagrangian value Q_lambda(s, a) = Q_R(s, a) - lambda * Q_C(s, a).
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
            double qR = dot(wReward[a], phi);
            double qC = dot(wCost[a], phi);
            double qLam = qR - lambda * qC;

            if (qLam > bestVal + 1e-9) {
                bestVal = qLam;
                bestAct = a;
                ties = 1;
            } else if (Math.abs(qLam - bestVal) <= 1e-9) {
                ties++;
                if (rng.nextInt(ties) == 0) {
                    bestAct = a;
                }
            }
        }
        return bestAct;
    }

    /**
     * Safe-Anchored Counterfactual Dual-Update Shielded Learning penalty:
     * Anchors the counterfactual target strictly below the executed safe action's actual
     * (Q_R(s, a_safe), Q_C(s, a_safe)) with margin, guaranteeing Q_lambda(s, unsafe) < Q_lambda(s, safe)
     * for all lambda >= 0 while staying localized to congested state features (k = 1..NUM_FEATURES-1).
     */
    public void applyShieldPenaltyUpdate(double[] stateVec, int unsafeAction, int safeAction) {
        if (unsafeAction < 0 || unsafeAction >= NUM_ACTIONS) return;
        int safeIdx = Math.max(0, Math.min(NUM_ACTIONS - 1, safeAction));
        double[] phi = extractFeatures(stateVec);
        double step = (3.0 * cfg.rlAlpha) / normSqLocalized(phi);

        double qRSafe = dot(wReward[safeIdx], phi);
        double qCSafe = dot(wCost[safeIdx], phi);
        double targetR = Math.min(-1.5, qRSafe - 1.5);
        double targetC = Math.max(1.5, qCSafe + 2.0);

        double curR = dot(wReward[unsafeAction], phi);
        double curC = dot(wCost[unsafeAction], phi);
        double errR = clamp(targetR - curR, -12.0, 12.0);
        double errC = clamp(targetC - curC, -12.0, 12.0);

        for (int k = 1; k < NUM_FEATURES; k++) {
            wReward[unsafeAction][k] = (1.0 - cfg.rlWeightDecay) * wReward[unsafeAction][k] + step * errR * phi[k];
            wCost[unsafeAction][k]   = (1.0 - cfg.rlWeightDecay) * wCost[unsafeAction][k]   + step * errC * phi[k];
        }
    }

    /**
     * Primal-Dual Expected SARSA TD update over executed transition (s, a_exec, r, c, s').
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
        double expectedNextC = 0.0;
        for (int a = 0; a < NUM_ACTIONS; a++) {
            if (evalMask != null && !evalMask[a]) continue;
            double prob = epsNext / validCount;
            if (a == greedyNext) {
                prob += (1.0 - epsNext);
            }
            expectedNextR += prob * dot(wReward[a], phiNext);
            expectedNextC += prob * dot(wCost[a], phiNext);
        }

        double targetR = reward + cfg.rlGamma * expectedNextR;
        double targetC = cost + cfg.rlGamma * expectedNextC;

        double predR = dot(wReward[executedAction], phiPrev);
        double predC = dot(wCost[executedAction], phiPrev);

        double deltaR = clamp(targetR - predR, -5.0, 5.0);
        double deltaC = clamp(targetC - predC, -5.0, 5.0);

        double step = cfg.rlAlpha / normSq(phiPrev);
        for (int k = 0; k < NUM_FEATURES; k++) {
            wReward[executedAction][k] = (1.0 - cfg.rlWeightDecay) * wReward[executedAction][k] + step * deltaR * phiPrev[k];
            wCost[executedAction][k]   = (1.0 - cfg.rlWeightDecay) * wCost[executedAction][k]   + step * deltaC * phiPrev[k];
        }

        // Dual Lagrange multiplier update
        this.lambda = clamp(this.lambda + cfg.rlEtaLambda * (cost - cfg.rlTargetCost), 0.0, cfg.rlLambdaMax);

        this.epochCount++;
        this.lastReward = reward;
        this.lastCost = cost;
        this.rewardSum += reward;
        this.costSum += cost;
    }

    /**
     * Opportunistic Mobility-Class Gossip Consensus averaging.
     */
    public boolean gossipMerge(ShieldedCmdpRlAgent peer, int myClass, int peerClass) {
        if (peer == null || peer.epochCount <= 0) {
            return false;
        }
        if (cfg.gossipSameClassOnly && myClass != peerClass) {
            return false;
        }

        double rawAlphaG = cfg.gossipWeightMax * peer.epochCount / (this.epochCount + peer.epochCount + 1.0);
        double alphaG = clamp(rawAlphaG, cfg.gossipWeightMin, cfg.gossipWeightMax);
        for (int a = 0; a < NUM_ACTIONS; a++) {
            for (int k = 0; k < NUM_FEATURES; k++) {
                this.wReward[a][k] = (1.0 - alphaG) * this.wReward[a][k] + alphaG * peer.wReward[a][k];
                this.wCost[a][k]   = (1.0 - alphaG) * this.wCost[a][k]   + alphaG * peer.wCost[a][k];
            }
        }
        this.lambda = clamp((1.0 - alphaG) * this.lambda + alphaG * peer.lambda, 0.0, cfg.rlLambdaMax);
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
