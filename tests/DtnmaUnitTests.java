/*
 * DtnmaUnitTests.java
 * Zero-dependency unit test suite for ADTNM Phase 1 Safety Shield,
 * Hazard-Localized Counterfactual Shaping, Symmetric Gossip, Causal ACK Credit,
 * 16B Contact Beacons, Relay-Time Token Conservation, and All-Critical Zero-Overshoot.
 */
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.ModuleCommunicationBus;
import core.MovementListener;
import core.NetworkInterface;
import core.Settings;
import movement.MovementModel;
import movement.StationaryMovement;
import routing.ControlAction;
import routing.DtnmaConfig;
import routing.DtnmaContactBeacon;
import routing.DtnmaPolicyProfile;
import routing.DtnmaRouter;
import routing.DtnmaSafetyShield;
import routing.DtnmaShieldedAckRLRouter;
import routing.EddTelemetrySampler;
import routing.PredicateExpression;
import routing.PrioritySprayAndWaitRouter;
import routing.ShieldedCmdpRlAgent;
import routing.StateBasedRule;

public class DtnmaUnitTests {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String name, String detail) {
        if (cond) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.err.println("[FAIL] " + name + " -> " + detail);
        }
    }

    private static class StubSampler extends EddTelemetrySampler {
        double q = 0.0;
        double drift = 0.0;
        double dens = 0.0;
        double soc = 1.0;

        StubSampler() {
            super(null, null);
        }

        @Override
        public double getEddValue(String eddName) {
            if (EDD_QUEUE_RATIO.equals(eddName)) return q;
            if (EDD_QUEUE_DRIFT.equals(eddName)) return drift;
            if (EDD_NODE_DENSITY.equals(eddName)) return dens;
            if (EDD_ENERGY_SOC.equals(eddName)) return soc;
            return 0.0;
        }
    }

    private static void test1SafetyShieldInvariantsAndLiveness() {
        DtnmaConfig cfg = new DtnmaConfig();
        DtnmaSafetyShield shield = new DtnmaSafetyShield(cfg);
        StubSampler sampler = new StubSampler();

        PredicateExpression pCrit = new PredicateExpression(true);
        pCrit.addClause(EddTelemetrySampler.EDD_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, 0.86);
        StateBasedRule critRule = new StateBasedRule("ARI://DTNMA/SRL/TEST_CRIT", "Test critical rule", 100, pCrit);
        critRule.addAction(ControlAction.setSprayQuota(2));
        critRule.addAction(ControlAction.setIngressMode(1));
        List<StateBasedRule> rules = Collections.singletonList(critRule);

        // S1 Critical Q >= 0.94 -> only action 0 admissible
        sampler.q = 0.95; sampler.drift = 0.0; sampler.dens = 1.0; sampler.soc = 1.0;
        boolean[] mCrit = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        boolean onlyZero = mCrit[0];
        for (int a = 1; a < DtnmaPolicyProfile.NUM_ACTIONS; a++) {
            onlyZero = onlyZero && !mCrit[a];
        }
        DtnmaSafetyShield.ShieldDecision dCrit = shield.enforce(7, 7, 0, sampler, rules);
        check(onlyZero && dCrit.getExecutedAction() == 0 && dCrit.isIntervened(),
                "Test1a: Shield S1 critical Q>=0.94 forces Profile 0 & preserves liveness",
                "mask=" + Arrays.toString(mCrit));

        // S2 Low Energy SoC < 0.20 -> restrict to {0, 1} with mask[0]=true
        sampler.q = 0.30; sampler.soc = 0.15;
        boolean[] mEnergy = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        check(mEnergy[0] && mEnergy[1] && !mEnergy[2] && !mEnergy[7],
                "Test1b: Shield S2 energy SoC<0.20 survival failsafe restricts to {0,1}",
                "mask=" + Arrays.toString(mEnergy));

        // S3 Dense Storm Guard (Q >= 0.78 && Density >= 5.0) -> blocks profiles 6,7
        sampler.q = 0.80; sampler.soc = 1.0; sampler.dens = 6.0;
        boolean[] mStorm = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        check(mStorm[0] && mStorm[4] && !mStorm[6] && !mStorm[7],
                "Test1c: Shield S3 dense storm guard blocks high-spray {6,7}",
                "mask=" + Arrays.toString(mStorm));

        // S4 Critical Declarative Rule (Q=0.87 triggers maxQuota=2 -> blocks profiles >= 2)
        sampler.q = 0.87; sampler.dens = 2.0;
        boolean[] mRule = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        check(mRule[0] && mRule[1] && !mRule[2] && !mRule[4],
                "Test1d: Shield S4 priority-100 rule enforces quota<=2 while keeping liveness",
                "mask=" + Arrays.toString(mRule));
    }

    private static void test2CounterfactualShapingAndLocalization() {
        DtnmaConfig cfg = new DtnmaConfig();
        cfg.rlLambdaInit = 5.0; // Max stress dual multiplier
        cfg.rlLambdaMax = 5.0;
        ShieldedCmdpRlAgent agent = new ShieldedCmdpRlAgent(49L, 1, cfg);

        double[] sCong = new double[] { 0.95, 0.70, 0.50, 0.20, 0.50, 1.0 };
        double[] sHealthy = new double[] { 0.25, 0.50, 0.15, 0.30, 0.50, 1.0 };
        boolean[] maskZeroOnly = new boolean[DtnmaPolicyProfile.NUM_ACTIONS];
        maskZeroOnly[0] = true;

        // Drive high discounted Q_C(s_cong, 0) ~ 6.0 and train healthy reward on Profile 4
        for (int i = 0; i < 40; i++) {
            agent.updateTransition(sCong, 0, 0.0, 1.0, sCong, maskZeroOnly, true, i * 15.0);
            agent.updateTransition(sHealthy, 4, 1.5, 0.02, sHealthy, null, false, i * 15.0);
        }

        // Apply safe-anchored counterfactual penalty updates on unsafe action 7 vs safe 0
        for (int i = 0; i < 6; i++) {
            agent.applyShieldPenaltyUpdate(sCong, 7, 0);
        }

        double qLamUnsafeCong = agent.getLagrangianValue(sCong, 7);
        double qLamSafeCong = agent.getLagrangianValue(sCong, 0);
        int healthyGreedy = agent.proposeGreedyAction(sHealthy);

        check(qLamUnsafeCong < qLamSafeCong,
                "Test2a: Safe-Anchored shaping drives Q_lambda(s_cong, unsafe) < Q_lambda(s_cong, safe) at lambda=5, Q_C~6",
                String.format("unsafe=%.3f safe=%.3f", qLamUnsafeCong, qLamSafeCong));

        check(healthyGreedy != 0 && healthyGreedy == 4,
                "Test2b: Hazard-localized penalty keeps healthy Q=0.25 regime anchored to optimal Profile 4",
                "healthyGreedy=" + healthyGreedy);
    }

    private static void test3SymmetricAtomicGossipConsensus() {
        DtnmaConfig cfg = new DtnmaConfig();
        cfg.gossipMinEpochs = 15;

        ShieldedCmdpRlAgent earlyA = new ShieldedCmdpRlAgent(1L, 0, cfg);
        ShieldedCmdpRlAgent earlyB = new ShieldedCmdpRlAgent(1L, 1, cfg);
        double[] s = new double[] { 0.40, 0.50, 0.25, 0.30, 0.50, 1.0 };
        for (int i = 0; i < 5; i++) {
            earlyA.updateTransition(s, 4, 1.0, 0.05, s, null, false, i * 15.0);
            earlyB.updateTransition(s, 3, 0.8, 0.05, s, null, false, i * 15.0);
        }
        boolean blockedEarly = !ShieldedCmdpRlAgent.gossipMergeSymmetric(earlyA, earlyB, 1, 1);
        check(blockedEarly,
                "Test3a: gossipMinEpochs guard blocks premature gossip (<15 epochs)",
                "merged prematurely");

        // Prepare identical pairs (a1, b1) and (a2, b2) trained differently
        ShieldedCmdpRlAgent a1 = new ShieldedCmdpRlAgent(1L, 10, cfg);
        ShieldedCmdpRlAgent a2 = new ShieldedCmdpRlAgent(1L, 10, cfg);
        ShieldedCmdpRlAgent b1 = new ShieldedCmdpRlAgent(1L, 20, cfg);
        ShieldedCmdpRlAgent b2 = new ShieldedCmdpRlAgent(1L, 20, cfg);

        for (int i = 0; i < 20; i++) {
            a1.updateTransition(s, 6, 1.4, 0.05, s, null, false, i * 15.0);
            a2.updateTransition(s, 6, 1.4, 0.05, s, null, false, i * 15.0);
            b1.updateTransition(s, 2, 0.5, 0.25, s, null, false, i * 15.0);
            b2.updateTransition(s, 2, 0.5, 0.25, s, null, false, i * 15.0);
        }

        boolean crossBlocked = !ShieldedCmdpRlAgent.gossipMergeSymmetric(a1, b1, 0, 2);
        boolean symAB = ShieldedCmdpRlAgent.gossipMergeSymmetric(a1, b1, 1, 1);
        boolean symBA = ShieldedCmdpRlAgent.gossipMergeSymmetric(b2, a2, 1, 1);

        boolean exactSym = Math.abs(a1.getLagrangianValue(s, 6) - a2.getLagrangianValue(s, 6)) < 1e-12
                && Math.abs(b1.getLagrangianValue(s, 2) - b2.getLagrangianValue(s, 2)) < 1e-12
                && Math.abs(a1.getLambda() - a2.getLambda()) < 1e-12;

        check(crossBlocked && symAB && symBA && exactSym,
                "Test3b: Symmetric atomic gossip is class-gated & strictly order-independent (A<->B == B<->A)",
                "crossBlocked=" + crossBlocked + " exactSym=" + exactSym);
    }

    private static void test4CausalDelayedAckEligibilityCredit() {
        DtnmaConfig cfg = new DtnmaConfig();
        ShieldedCmdpRlAgent agent = new ShieldedCmdpRlAgent(7L, 3, cfg);
        double[] sFwd = new double[] { 0.35, 0.50, 0.20, 0.40, 0.55, 1.0 };

        double beforeTarget = agent.getLagrangianValue(sFwd, 5);
        double beforeOther = agent.getLagrangianValue(sFwd, 2);
        agent.applyDelayedAckCredit(sFwd, 5, 1.2);
        double afterTarget = agent.getLagrangianValue(sFwd, 5);
        double afterOther = agent.getLagrangianValue(sFwd, 2);

        check(afterTarget > beforeTarget + 0.01 && Math.abs(afterOther - beforeOther) < 1e-12,
                "Test4: Causal Delayed-ACK eligibility credits forwarded (s, a) without leaking to other actions",
                String.format("deltaTarget=%.4f deltaOther=%.6f", afterTarget - beforeTarget, afterOther - beforeOther));
    }

    private static void test5BeaconQuantizationAndWireSizes() {
        boolean sizesOk = (DtnmaContactBeacon.BEACON_SIZE_BYTES == 16)
                && (DtnmaContactBeacon.ACK_ID_BYTES == 4)
                && (DtnmaContactBeacon.GOSSIP_PAYLOAD_BYTES == 1292);

        DtnmaContactBeacon b = new DtnmaContactBeacon(42, 1234.5, 0.731, -0.125, 0.855, 12.345, 2, 7);
        boolean roundTripOk = b.getHostAddress() == 42
                && Math.abs(b.getTimestamp() - 1234.5) < 0.01
                && Math.abs(b.getQueueRatio() - 0.731) < 1e-4
                && Math.abs(b.getQueueDriftEwma() - (-0.125)) < 1e-4
                && Math.abs(b.getEnergySoc() - 0.855) < 1e-4
                && Math.abs(b.getNodeSpeed() - 12.345) <= 0.001
                && b.getMobilityClass() == 2
                && b.getActiveDegree() == 7;

        check(sizesOk && roundTripOk,
                "Test5: ContactBeacon 16B wire quantization & exact gossip size (1292B)",
                "sizesOk=" + sizesOk + " roundTripOk=" + roundTripOk);
    }

    private static void test6BinaryTokenConservationAtRelay() {
        boolean ok = true;
        String err = "";
        for (int copies = 2; copies <= 8; copies++) {
            for (int quota = 1; quota <= 8; quota++) {
                int bulkSend = DtnmaRouter.computeRelaySplit(copies, quota, false);
                int bulkRemain = copies - bulkSend;
                if (quota == 1 && bulkSend != 0) {
                    ok = false; err = "bulkSend!=0 at quota=1";
                }
                if (quota >= 2 && (bulkSend < 1 || bulkRemain < 1 || bulkSend + bulkRemain != copies)) {
                    ok = false; err = "conservation failed at copies=" + copies + " quota=" + quota;
                }

                int critSend = DtnmaRouter.computeRelaySplit(copies, quota, true);
                int critRemain = copies - critSend;
                if (critSend != copies / 2 || critSend + critRemain != copies || critRemain < 1) {
                    ok = false; err = "critical split failed at copies=" + copies;
                }
            }
        }
        check(ok, "Test6: Relay-time adaptive split enforces exact Binary Token Conservation", err);
    }

    private static void test7AllCriticalFullBufferZeroOvershoot() {
        String cfgText =
                "Scenario.endTime = 100\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "Group.bufferSize = 40000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        Settings groupSettings = new Settings("Group");
        MovementModel mm = new StationaryMovement(groupSettings);
        DtnmaRouter proto = new DtnmaRouter(groupSettings);
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();

        DTNHost h0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "n", ifaces, new ModuleCommunicationBus(), mm, proto);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "n", ifaces, new ModuleCommunicationBus(), mm, proto);

        DtnmaRouter r0 = (DtnmaRouter) h0.getRouter();

        Message c1 = new Message(h0, h1, "CRITICAL_FAULT_1", 20000);
        Message c2 = new Message(h0, h1, "CRITICAL_FAULT_2", 20000);
        boolean ok1 = r0.createNewMessage(c1);
        boolean ok2 = r0.createNewMessage(c2);
        // Simulate sole-copy critical messages (copies == 1)
        c1.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 1);
        c2.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 1);

        // Attempt bulk creation & critical creation on full 40k sole-copy critical buffer
        Message b1 = new Message(h0, h1, "SCIENCE_DATA_1", 10000);
        Message c3 = new Message(h0, h1, "CRITICAL_FAULT_3", 15000);
        boolean bulkBlocked = !r0.createNewMessage(b1);
        boolean critBlockedNoOvershoot = !r0.createNewMessage(c3);
        double occAfterSoleBlock = r0.getBufferOccupancyRatio();

        boolean soleProtected = r0.hasMessage("CRITICAL_FAULT_1") && r0.hasMessage("CRITICAL_FAULT_2");

        // Now give c2 redundant replicas (copies = 4) and verify emergency reclamation succeeds without overshoot
        c2.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 4);
        Message c4 = new Message(h0, h1, "CRITICAL_FAULT_4", 15000);
        boolean reclaimedRedundant = r0.createNewMessage(c4);
        double occAfterReclaim = r0.getBufferOccupancyRatio();

        boolean passedAll = ok1 && ok2 && bulkBlocked && critBlockedNoOvershoot
                && soleProtected && occAfterSoleBlock <= 1.000001
                && reclaimedRedundant && occAfterReclaim <= 1.000001
                && r0.hasMessage("CRITICAL_FAULT_1") && !r0.hasMessage("CRITICAL_FAULT_2");

        check(passedAll,
                "Test7: All-Critical Full-Buffer enforces occ <= 1.0 & protects sole critical copies",
                String.format("occ1=%.4f occ2=%.4f ok1=%s ok2=%s blkB=%s blkC=%s recl=%s",
                        occAfterSoleBlock, occAfterReclaim, ok1, ok2, bulkBlocked, critBlockedNoOvershoot, reclaimedRedundant));

        // Test8: localCreationRejected counter, PrioritySprayAndWaitRouter, & DtnmaShieldedAckRLRouter
        boolean rejCountOk = (r0.getLocalCreationRejected() == 2);

        String cfgSnW = cfgText + "SprayAndWaitRouter.nrofCopies = 6\nSprayAndWaitRouter.binaryMode = true\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgSnW.getBytes(StandardCharsets.UTF_8)));
        Settings grpSnW = new Settings("Group");
        MovementModel mm2 = new StationaryMovement(grpSnW);
        PrioritySprayAndWaitRouter snwProto = new PrioritySprayAndWaitRouter(grpSnW);
        DTNHost hs0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "p", ifaces, new ModuleCommunicationBus(), mm2, snwProto);
        DTNHost hs1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "p", ifaces, new ModuleCommunicationBus(), mm2, snwProto);
        PrioritySprayAndWaitRouter pSnW = (PrioritySprayAndWaitRouter) hs0.getRouter();

        Message psCrit1 = new Message(hs0, hs1, "CRITICAL_FAULT_P1", 20000);
        Message psCrit2 = new Message(hs0, hs1, "CRITICAL_FAULT_P2", 20000);
        boolean psOk1 = pSnW.createNewMessage(psCrit1);
        boolean psOk2 = pSnW.createNewMessage(psCrit2);
        psCrit1.updateProperty(PrioritySprayAndWaitRouter.MSG_COUNT_PROPERTY, 1);
        psCrit2.updateProperty(PrioritySprayAndWaitRouter.MSG_COUNT_PROPERTY, 1);
        Message psBulk = new Message(hs0, hs1, "SCIENCE_DATA_P1", 10000);
        boolean psBlkBulk = !pSnW.createNewMessage(psBulk);
        boolean psOccOk = pSnW.getBufferOccupancyRatio() <= 1.000001
                && pSnW.getLocalCreationRejected() == 1
                && pSnW.hasMessage("CRITICAL_FAULT_P1")
                && pSnW.hasMessage("CRITICAL_FAULT_P2");

        DtnmaShieldedAckRLRouter ackRl = new DtnmaShieldedAckRLRouter(grpSnW);
        boolean ackRlFlagsOk = ackRl.getIntelligenceMode() == DtnmaRouter.IntelligenceMode.SHIELDED_CMDP_RL
                && !ackRl.isGossipEnabled()
                && ackRl.isAckFeedbackEnabled();

        check(rejCountOk && psOk1 && psOk2 && psBlkBulk && psOccOk && ackRlFlagsOk,
                "Test8: localCreationRejected audit, PrioritySprayAndWaitRouter protection & DtnmaShieldedAckRLRouter flags",
                String.format("rej=%d psOk=(%s,%s,%s,%s) flags=%s",
                        r0.getLocalCreationRejected(), psOk1, psOk2, psBlkBulk, psOccOk, ackRlFlagsOk));
    }

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("ADTNM PHASE 1 JAVA UNIT TEST SUITE");
        System.out.println("=================================================================");
        test1SafetyShieldInvariantsAndLiveness();
        test2CounterfactualShapingAndLocalization();
        test3SymmetricAtomicGossipConsensus();
        test4CausalDelayedAckEligibilityCredit();
        test5BeaconQuantizationAndWireSizes();
        test6BinaryTokenConservationAtRelay();
        test7AllCriticalFullBufferZeroOvershoot();
        System.out.println("=================================================================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed.");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
