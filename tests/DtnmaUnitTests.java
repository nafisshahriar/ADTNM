/*
 * DtnmaUnitTests.java
 * Zero-dependency unit test suite for ADTNM Single-Critic Safety Shield,
 * Hazard-Localized Counterfactual Shaping, Symmetric Gossip (648B), Causal ACK Credit,
 * 16B Contact Beacons, Relay-Time Token Conservation, All-Critical Zero-Overshoot,
 * and Fair Baselines (PriorityMaxPropRouter & DtnmaStaticAckTunedRouter).
 */
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.ModuleCommunicationBus;
import core.MovementListener;
import core.NetworkInterface;
import core.Settings;
import core.SimClock;
import core.SimScenario;
import report.DtnmaRlMetricsReport;
import interfaces.ConnectivityGrid;
import interfaces.SimpleBroadcastInterface;
import movement.MovementModel;
import movement.StationaryMovement;
import routing.ControlAction;
import routing.DtnmaAgent;
import routing.DtnmaConfig;
import routing.DtnmaContactBeacon;
import routing.DtnmaFixedAckRouter;
import routing.DtnmaM0bStaticRouter;
import routing.DtnmaM0cV1GuardRouter;
import routing.DtnmaM0dConservativeRouter;
import routing.DtnmaM0eEnergy20Router;
import routing.DtnmaM0eEnergy25Router;
import routing.DtnmaM1FloodGroupRouter;
import routing.DtnmaM1FloodRouter;
import routing.DtnmaM1RemoteRouter;
import routing.util.EnergyModel;
import routing.DtnmaM2LocalOnlyRouter;
import routing.DtnmaM2SrlRouter;
import routing.DtnmaM3RlRouter;
import routing.DtnmaManagedRouter;
import routing.DtnmaPolicyProfile;
import routing.DtnmaRouter;
import routing.DtnmaRuleParser;
import routing.DtnmaSafetyShield;
import routing.DtnmaShieldedAckRLRouter;
import routing.DtnmaStaticAckL2Router;
import routing.DtnmaStaticAckL4Router;
import routing.DtnmaStaticAckL8Router;
import routing.DtnmaStaticAckL12Router;
import routing.DtnmaStaticAckTunedRouter;
import routing.EddTelemetrySampler;
import routing.EnergyAwareEpidemicRouter;
import routing.ManagedEpidemicRouter;
import routing.ManagedMaxPropRouter;
import routing.ManagedProphetV2Router;
import routing.ManagedSprayAndWaitRouter;
import routing.PredicateExpression;
import routing.PriorityMaxPropRouter;
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
        double critQ = 0.0;
        double drift = 0.0;
        double dens = 0.0;
        double soc = 1.0;

        StubSampler() {
            super(null, null);
        }

        @Override
        public double getEddValue(String eddName) {
            if (EDD_QUEUE_RATIO.equals(eddName)) return q;
            if (EDD_CRIT_QUEUE_RATIO.equals(eddName)) return critQ;
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

        // S1 Critical saturation (CritQ >= 0.50) OR active surge (Q>=0.94 && dQ>=0.015) -> only {0}
        // Whereas steady full evictable bulk cache (Q=0.95, CritQ=0.05, dQ=0.0) keeps P4 admissible!
        sampler.q = 0.95; sampler.critQ = 0.55; sampler.drift = 0.0; sampler.dens = 1.0; sampler.soc = 1.0;
        boolean[] mCrit = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        boolean onlyZeroCrit = mCrit[0];
        for (int a = 1; a < DtnmaPolicyProfile.NUM_ACTIONS; a++) {
            onlyZeroCrit = onlyZeroCrit && !mCrit[a];
        }
        DtnmaSafetyShield.ShieldDecision dCrit = shield.enforce(7, 7, 0, sampler, rules);

        sampler.q = 0.95; sampler.critQ = 0.05; sampler.drift = 0.0;
        boolean[] mSteadyBulk = shield.computeSafeActionMask(sampler, Collections.<StateBasedRule>emptyList(), new StringBuilder());
        sampler.drift = 0.02;
        boolean[] mSurge = shield.computeSafeActionMask(sampler, Collections.<StateBasedRule>emptyList(), new StringBuilder());
        boolean onlyZeroSurge = mSurge[0] && !mSurge[1] && !mSurge[4];
        check(onlyZeroCrit && dCrit.getExecutedAction() == 0 && dCrit.isIntervened()
                && mSteadyBulk[4] && onlyZeroSurge,
                "Test1a: Shield S1 distinguishes CritQ/active-surge {0} from steady full bulk cache (P4 admissible)",
                "crit=" + Arrays.toString(mCrit) + " steady=" + Arrays.toString(mSteadyBulk) + " surge=" + Arrays.toString(mSurge));

        // S2 Low Energy SoC < 0.20 -> restrict to {0, 1} with mask[0]=true
        sampler.q = 0.30; sampler.critQ = 0.0; sampler.drift = 0.0; sampler.soc = 0.15;
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
        ShieldedCmdpRlAgent agent = new ShieldedCmdpRlAgent(49L, 1, cfg);

        double[] sCong = new double[] { 0.95, 0.70, 0.50, 0.20, 0.50, 1.0 };
        double[] sHealthy = new double[] { 0.25, 0.50, 0.15, 0.30, 0.50, 1.0 };
        boolean[] maskZeroOnly = new boolean[DtnmaPolicyProfile.NUM_ACTIONS];
        maskZeroOnly[0] = true;

        // Train safe congestion profile 0 and healthy reward on Profile 4
        for (int i = 0; i < 40; i++) {
            agent.updateTransition(sCong, 0, 0.0, 1.0, sCong, maskZeroOnly, true, i * 15.0);
            agent.updateTransition(sHealthy, 4, 1.5, 0.02, sHealthy, null, false, i * 15.0);
        }

        // Apply safe-anchored counterfactual penalty updates on unsafe action 7 vs safe 0
        for (int i = 0; i < 6; i++) {
            agent.applyShieldPenaltyUpdate(sCong, 7, 0);
        }

        double qUnsafeCong = agent.getLagrangianValue(sCong, 7);
        double qSafeCong = agent.getLagrangianValue(sCong, 0);
        int healthyGreedy = agent.proposeGreedyAction(sHealthy);

        check(qUnsafeCong < qSafeCong,
                "Test2a: Safe-Anchored shaping drives Q(s_cong, unsafe) < Q(s_cong, safe) in single-critic mode",
                String.format("unsafe=%.3f safe=%.3f", qUnsafeCong, qSafeCong));

        boolean steadyPhi16Zero = agent.extractFeatures(
                new double[] { 0.95, 0.50, 0.20, 0.20, 0.50, 1.0 })[16] == 0.0;
        check(healthyGreedy != 0 && healthyGreedy == 4 && steadyPhi16Zero,
                "Test2b: Hazard-localized penalty keeps healthy Q=0.25 at Profile 4 & phi[16]=0 on steady full bulk",
                "healthyGreedy=" + healthyGreedy + " steadyPhi16Zero=" + steadyPhi16Zero);
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
                && Math.abs(a1.getLambda()) < 1e-12
                && Math.abs(b1.getLambda()) < 1e-12;

        check(crossBlocked && symAB && symBA && exactSym,
                "Test3b: Symmetric atomic gossip is class-gated, lambda=0.0, & strictly order-independent (A<->B == B<->A)",
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
                && (DtnmaContactBeacon.GOSSIP_PAYLOAD_BYTES == 648);

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
                "Test5: ContactBeacon 16B wire quantization & exact single-critic gossip size (648B)",
                "sizesOk=" + sizesOk + " roundTripOk=" + roundTripOk);
    }

    private static void test6BinaryTokenConservationAtRelay() {
        boolean ok = true;
        String err = "";
        for (int copies = 2; copies <= 12; copies++) {
            for (int quota = 1; quota <= 12; quota++) {
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

        // Even if c2 holds copies = 4 spray tokens, it is still un-ACKed and MUST stay protected (admitted bundles never evicted)
        c2.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 4);
        Message c4 = new Message(h0, h1, "CRITICAL_FAULT_4", 15000);
        boolean unackedBlocked = !r0.createNewMessage(c4);
        double occAfterUnacked = r0.getBufferOccupancyRatio();

        boolean passedAll = ok1 && ok2 && bulkBlocked && critBlockedNoOvershoot
                && soleProtected && occAfterSoleBlock <= 1.000001
                && unackedBlocked && occAfterUnacked <= 1.000001
                && r0.hasMessage("CRITICAL_FAULT_1") && r0.hasMessage("CRITICAL_FAULT_2");

        check(passedAll,
                "Test7: All-Critical Full-Buffer enforces occ <= 1.0 & never evicts un-ACKed critical copies",
                String.format("occ1=%.4f occ2=%.4f ok1=%s ok2=%s blkB=%s blkC=%s unackBlk=%s",
                        occAfterSoleBlock, occAfterUnacked, ok1, ok2, bulkBlocked, critBlockedNoOvershoot, unackedBlocked));

        // Test8: localCreationRejected counter, PrioritySprayAndWaitRouter, & DtnmaShieldedAckRLRouter
        boolean rejCountOk = (r0.getLocalCreationRejected() == 3);

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

    private static void test9ProtectedFifoEvictionAndCriticalOccupancy() {
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

        Message crit = new Message(h0, h1, "CRITICAL_FAULT_PFIFO", 20000);
        crit.setReceiveTime(1.0);
        boolean okCrit = r0.createNewMessage(crit);
        double critOccHalf = r0.getCriticalOccupancyRatio();

        Message bOld = new Message(h0, h1, "SCIENCE_DATA_OLD", 10000);
        bOld.setReceiveTime(5.0);
        boolean okOld = r0.createNewMessage(bOld);

        Message bNew = new Message(h0, h1, "SCIENCE_DATA_NEW", 10000);
        bNew.setReceiveTime(10.0);
        boolean okNew = r0.createNewMessage(bNew);
        bNew.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 1);

        Message bThird = new Message(h0, h1, "SCIENCE_DATA_THIRD", 10000);
        bThird.setReceiveTime(15.0);
        boolean okThird = r0.createNewMessage(bThird);

        boolean critOccOk = Math.abs(critOccHalf - 0.50) < 1e-6
                && Math.abs(r0.getCriticalOccupancyRatio() - 0.50) < 1e-6;
        boolean fifoOk = okCrit && okOld && okNew && okThird
                && r0.hasMessage("CRITICAL_FAULT_PFIFO")
                && !r0.hasMessage("SCIENCE_DATA_OLD")
                && r0.hasMessage("SCIENCE_DATA_NEW")
                && r0.hasMessage("SCIENCE_DATA_THIRD")
                && r0.getBufferOccupancyRatio() <= 1.000001;

        check(critOccOk && fifoOk,
                "Test9: Protected FIFO evicts oldest bulk before copies=1 while protecting critical & reporting critical occupancy",
                String.format("critOccHalf=%.4f fifoOk=%s hasCrit=%s hasOld=%s hasNew=%s hasThird=%s occ=%.4f",
                        critOccHalf, fifoOk, r0.hasMessage("CRITICAL_FAULT_PFIFO"),
                        r0.hasMessage("SCIENCE_DATA_OLD"), r0.hasMessage("SCIENCE_DATA_NEW"),
                        r0.hasMessage("SCIENCE_DATA_THIRD"), r0.getBufferOccupancyRatio()));
    }

    private static void test10PriorityMaxPropAndTunedStatic() {
        String cfgText =
                "Scenario.endTime = 100\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "Group.bufferSize = 40000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "MaxPropRouter.alpha = 1.0\n" +
                "MaxPropRouter.probSetMaxSize = 50\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        Settings grp = new Settings("Group");
        MovementModel mm = new StationaryMovement(grp);
        PriorityMaxPropRouter mpProto = new PriorityMaxPropRouter(grp);
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();

        DTNHost h0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m", ifaces, new ModuleCommunicationBus(), mm, mpProto);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m", ifaces, new ModuleCommunicationBus(), mm, mpProto);
        PriorityMaxPropRouter pm0 = (PriorityMaxPropRouter) h0.getRouter();

        Message c1 = new Message(h0, h1, "CRITICAL_FAULT_MP1", 20000);
        Message c2 = new Message(h0, h1, "CRITICAL_FAULT_MP2", 20000);
        boolean ok1 = pm0.createNewMessage(c1);
        boolean ok2 = pm0.createNewMessage(c2);
        Message blkBulk = new Message(h0, h1, "SCIENCE_DATA_MP_BLK", 10000);
        Message blkCrit = new Message(h0, h1, "CRITICAL_FAULT_MP3", 15000);
        boolean deniedBulk = !pm0.createNewMessage(blkBulk);
        boolean deniedCrit = !pm0.createNewMessage(blkCrit);

        // Mixed critical + bulk host: new bulk evicts old bulk while strictly keeping critical
        DTNHost hMix = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m", ifaces, new ModuleCommunicationBus(), mm, mpProto);
        PriorityMaxPropRouter pmMix = (PriorityMaxPropRouter) hMix.getRouter();
        Message mc = new Message(hMix, h1, "CRITICAL_FAULT_MIX", 20000);
        mc.setReceiveTime(1.0);
        Message mb1 = new Message(hMix, h1, "SCIENCE_DATA_MIX1", 20000);
        mb1.setReceiveTime(5.0);
        Message mb2 = new Message(hMix, h1, "SCIENCE_DATA_MIX2", 10000);
        mb2.setReceiveTime(10.0);
        boolean mixOk = pmMix.createNewMessage(mc) && pmMix.createNewMessage(mb1) && pmMix.createNewMessage(mb2)
                && pmMix.hasMessage("CRITICAL_FAULT_MIX")
                && !pmMix.hasMessage("SCIENCE_DATA_MIX1")
                && pmMix.hasMessage("SCIENCE_DATA_MIX2")
                && pmMix.getBufferOccupancyRatio() <= 1.000001;

        boolean mpOk = ok1 && ok2 && deniedBulk && deniedCrit
                && pm0.hasMessage("CRITICAL_FAULT_MP1") && pm0.hasMessage("CRITICAL_FAULT_MP2")
                && pm0.getLocalCreationRejected() == 2
                && pm0.getBufferOccupancyRatio() <= 1.000001
                && mixOk;

        DtnmaStaticAckTunedRouter tunedProto = new DtnmaStaticAckTunedRouter(grp);
        DTNHost hTuned = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "t", ifaces, new ModuleCommunicationBus(), mm, tunedProto);
        DtnmaStaticAckTunedRouter rTuned = (DtnmaStaticAckTunedRouter) hTuned.getRouter();
        rTuned.update();
        boolean tunedOk = (rTuned.getIntelligenceMode() == DtnmaRouter.IntelligenceMode.STATIC_RULES)
                && !rTuned.isGossipEnabled()
                && rTuned.isAckFeedbackEnabled()
                && rTuned.getCurrentSprayQuota() == 10;

        check(mpOk && tunedOk,
                "Test10: PriorityMaxProp zero-drop & cap enforcement + DtnmaStaticAckTunedRouter L=10 load",
                String.format("mpOk=%s mixOk=%s rej=%d occ=%.4f tunedOk=%s quota=%d",
                        mpOk, mixOk, pm0.getLocalCreationRejected(), pm0.getBufferOccupancyRatio(),
                        tunedOk, rTuned.getCurrentSprayQuota()));
    }

    private static void test11ComponentLadderAndDownlinkControl() {
        String cfgText =
                "Scenario.endTime = 100\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "Group.bufferSize = 100000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        Settings grp = new Settings("Group");
        MovementModel mm = new StationaryMovement(grp);
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();

        DtnmaFixedAckRouter fixedProto = new DtnmaFixedAckRouter(grp);
        DTNHost hFix = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "f", ifaces, new ModuleCommunicationBus(), mm, fixedProto);
        DtnmaFixedAckRouter rFix = (DtnmaFixedAckRouter) hFix.getRouter();
        rFix.update();
        boolean fixedOk = (rFix.getIntelligenceMode() == DtnmaRouter.IntelligenceMode.STATIC_RULES)
                && !rFix.isGossipEnabled()
                && rFix.isAckFeedbackEnabled()
                && rFix.getCurrentSprayQuota() == 6
                && rFix.getDecisionCpuCount() >= 1;

        DtnmaStaticAckL2Router l2Proto = new DtnmaStaticAckL2Router(grp);
        DTNHost hL2 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "l2", ifaces, new ModuleCommunicationBus(), mm, l2Proto);
        DtnmaStaticAckL2Router rL2 = (DtnmaStaticAckL2Router) hL2.getRouter();
        rL2.update();

        DtnmaStaticAckL4Router l4Proto = new DtnmaStaticAckL4Router(grp);
        DTNHost hL4 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "l4", ifaces, new ModuleCommunicationBus(), mm, l4Proto);
        DtnmaStaticAckL4Router rL4 = (DtnmaStaticAckL4Router) hL4.getRouter();
        rL4.update();

        boolean l2l4Ok = (rL2.getCurrentSprayQuota() == 2) && (rL4.getCurrentSprayQuota() == 4);

        boolean prefixOk = DtnmaRouter.isCriticalMessage("CTRL_UPDATE_V1_1")
                && DtnmaRouter.isCriticalMessage("ALARM_1")
                && DtnmaRouter.isCriticalMessage("TELEM_CRIT_1")
                && DtnmaRouter.isDownlinkControlMessage(new Message(hFix, hL2, "CTRL_UPDATE_V1_1", 1024))
                && !DtnmaRouter.isTelemetryOrAlarmMessage(new Message(hFix, hL2, "CTRL_UPDATE_V1_1", 1024))
                && DtnmaRouter.isTelemetryOrAlarmMessage(new Message(hFix, hL2, "TELEM_CRIT_1", 1024));

        check(fixedOk && l2l4Ok && prefixOk,
                "Test11: Step-3 DtnmaFixedAckRouter, StaticAck L2/L4, CPU counter & CTRL/TELEM classification",
                String.format("fixedOk=%s l2Quota=%d l4Quota=%d prefixOk=%s",
                        fixedOk, rL2.getCurrentSprayQuota(), rL4.getCurrentSprayQuota(), prefixOk));
    }

    private static void test12DownlinkInBandControlAdoption() {
        String cfgText =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        Settings ifaceSettings = new Settings("bt");
        SimpleBroadcastInterface ifaceProto = new SimpleBroadcastInterface(ifaceSettings);
        ifaceProto.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);
        DtnmaRouter proto = new DtnmaRouter(grp);

        DTNHost hMgr = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m", Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);
        DTNHost hAgt = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "a", Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);

        DtnmaRouter rMgr = (DtnmaRouter) hMgr.getRouter();
        DtnmaRouter rAgt = (DtnmaRouter) hAgt.getRouter();

        hMgr.forceConnection(hAgt, null, true);

        Message cmdV1 = new Message(hMgr, hAgt, "CTRL_UPDATE_V1_1", 4096);
        rMgr.createNewMessage(cmdV1);
        for (int step = 0; step < 30; step++) {
            SimClock.getInstance().advance(0.1);
            hMgr.update(false);
            hAgt.update(false);
        }
        boolean v1Adopted = (rAgt.getAppliedControlVersion() == 1)
                && (rAgt.getConfig().shieldEnergyMin >= 0.25)
                && rAgt.getRuleDatabase().stream().anyMatch(r -> "ARI://DTNMA/SRL/DOWNLINK_V1_ENERGY_GUARD".equals(r.getAri()));

        Message cmdV2 = new Message(hMgr, hAgt, "CTRL_UPDATE_V2_1", 4096);
        rMgr.createNewMessage(cmdV2);
        for (int step = 0; step < 30; step++) {
            SimClock.getInstance().advance(0.1);
            hMgr.update(false);
            hAgt.update(false);
        }
        boolean v2Adopted = (rAgt.getAppliedControlVersion() == 2)
                && (Math.abs(rAgt.getConfig().shieldEnergyMin - 0.20) < 1e-6)
                && rAgt.getRuleDatabase().stream().noneMatch(r -> "ARI://DTNMA/SRL/DOWNLINK_V1_ENERGY_GUARD".equals(r.getAri()));

        check(v1Adopted && v2Adopted,
                "Test12: In-band RFC 9675 Downlink CTRL_UPDATE V1/V2 delivery triggers live shield & SRL reconfig",
                String.format("v1Adopted=%s (ver=%d,minE=%.2f) v2Adopted=%s (ver=%d,minE=%.2f)",
                        v1Adopted, rAgt.getAppliedControlVersion(), rAgt.getConfig().shieldEnergyMin,
                        v2Adopted, rAgt.getAppliedControlVersion(), rAgt.getConfig().shieldEnergyMin));
    }

    private static void test13ComposedStockRoutersProtection() {
        String cfgText =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 50000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 8\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n" +
                "ProphetV2Router.secondsInTimeUnit = 30\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        Settings ifaceSettings = new Settings("bt");
        SimpleBroadcastInterface ifaceProto = new SimpleBroadcastInterface(ifaceSettings);
        ifaceProto.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);

        DtnmaManagedRouter[] protos = new DtnmaManagedRouter[] {
                new ManagedSprayAndWaitRouter(grp),
                new ManagedProphetV2Router(grp),
                new ManagedMaxPropRouter(grp),
                new ManagedEpidemicRouter(grp)
        };

        boolean allProtected = true;
        StringBuilder failDetail = new StringBuilder();
        for (int i = 0; i < protos.length; i++) {
            routing.MessageRouter proto = protos[i].getManagedActiveRouter();
            DTNHost h = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                    "w" + i, Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);
            DTNHost dst = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                    "d" + i, Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);
            DtnmaManagedRouter mr = (DtnmaManagedRouter) h.getRouter();
            DtnmaAgent ag = mr.getDtnmaAgent();

            // Fill 50KB buffer with 20KB un-ACKed critical + bulk, forcing bulk eviction
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "CRITICAL_FAULT_1_" + i, 20000));
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "BULK_1_" + i, 20000));
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "BULK_2_" + i, 20000));
            // Fill remaining space with critical to saturate
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "CRITICAL_FAULT_2_" + i, 25000));
            // Next critical (20KB) cannot fit even after dropping all bulk -> must reject cleanly and count criticalCreationRejected
            boolean extraCrit = mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "CRITICAL_FAULT_3_" + i, 20000));
            boolean critPreserved = mr.hasManagedMessage("CRITICAL_FAULT_1_" + i) && mr.hasManagedMessage("CRITICAL_FAULT_2_" + i);
            boolean occOk = mr.getBufferOccupancyRatio() <= 1.0 + 1e-9;
            boolean critRejTracked = !extraCrit && ag.getCriticalCreationRejected() >= 1;
            if (!critPreserved || !occOk || !critRejTracked) {
                allProtected = false;
                failDetail.append(mr.getClass().getSimpleName())
                          .append("{critPreserved=").append(critPreserved)
                          .append(",occ=").append(mr.getBufferOccupancyRatio())
                          .append(",critRej=").append(ag.getCriticalCreationRejected()).append("} ");
            }
        }
        check(allProtected,
                "Test13: Composed Managed{SnW,PRoPHETv2,MaxProp,Epidemic} protect un-ACKed critical & track split rejections",
                failDetail.toString());
    }

    private static void test14PolicyEquivalenceAndRuntimeRules() {
        String fixedL6Rule =
                "RULE: ARI://DTNMA/SRL/FIXED_L6 | PRIORITY: 10 | DESC: Fixed L=6\n" +
                "IF: (EDD_QUEUE_RATIO >= 0.0)\n" +
                "THEN: CTRL_SET_SPRAY_QUOTA(6); CTRL_SET_INGRESS_MODE(0); CTRL_SET_TELEMETRY_INTERVAL(60); CTRL_SET_BACKPRESSURE(false)\n";
        String cfgText =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 40000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 4\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n" +
                "DtnmaRouter.intelligenceMode = STATIC_RULES\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        Settings ifaceSettings = new Settings("bt");
        SimpleBroadcastInterface ifaceProto = new SimpleBroadcastInterface(ifaceSettings);
        ifaceProto.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);

        ManagedSprayAndWaitRouter mSnwProto = new ManagedSprayAndWaitRouter(grp);
        mSnwProto.getDtnmaAgent().loadRulesFromString(fixedL6Rule);
        PrioritySprayAndWaitRouter priProto = new PrioritySprayAndWaitRouter(grp);

        DTNHost hManaged = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "msnw", Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, mSnwProto);
        DTNHost hPri = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "psnw", Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, priProto);
        DTNHost hDst = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "dst", Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, priProto);

        ManagedSprayAndWaitRouter rM = (ManagedSprayAndWaitRouter) hManaged.getRouter();
        PrioritySprayAndWaitRouter rP = (PrioritySprayAndWaitRouter) hPri.getRouter();

        SimClock.getInstance().advance(2.0);
        hManaged.update(false);

        rM.createNewMessage(new Message(hManaged, hDst, "CRITICAL_FAULT_EQ", 15000));
        rP.createNewMessage(new Message(hPri, hDst, "CRITICAL_FAULT_EQ", 15000));
        rM.createNewMessage(new Message(hManaged, hDst, "BULK_EQ_1", 15000));
        rP.createNewMessage(new Message(hPri, hDst, "BULK_EQ_1", 15000));
        rM.createNewMessage(new Message(hManaged, hDst, "BULK_EQ_2", 15000));
        rP.createNewMessage(new Message(hPri, hDst, "BULK_EQ_2", 15000));

        boolean eqOk = rM.hasManagedMessage("CRITICAL_FAULT_EQ") == rP.hasMessage("CRITICAL_FAULT_EQ")
                && rM.getDtnmaAgent().getCurrentSprayQuota() == 6;

        String ruleL2 =
                "RULE: ARI://DTNMA/SRL/RELOAD_L2 | PRIORITY: 20 | DESC: Reloaded L=2\n" +
                "IF: (EDD_QUEUE_RATIO >= 0.0)\n" +
                "THEN: CTRL_SET_SPRAY_QUOTA(2); CTRL_SET_INGRESS_MODE(1); CTRL_SET_TELEMETRY_INTERVAL(60); CTRL_SET_BACKPRESSURE(true)\n";
        rM.getDtnmaAgent().loadRulesFromString(ruleL2);
        SimClock.getInstance().advance(2.0);
        hManaged.update(false);
        boolean reconfigOk = rM.getDtnmaAgent().getCurrentSprayQuota() == 2
                && rM.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_THROTTLE_BULK;

        check(eqOk && reconfigOk,
                "Test14: ManagedSprayAndWait matches PrioritySnW protection & supports runtime loadRulesFromString",
                "eqOk=" + eqOk + " reconfigOk=" + reconfigOk + " quota=" + rM.getDtnmaAgent().getCurrentSprayQuota());
    }

    private static void test15ManagementParadigmsM0M1M2() {
        String baseCfg =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 100000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 5\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "DtnmaRouter.staticQuota = 6\n" +
                "DtnmaRouter.epidemicControlFlooding = true\n";
        Settings.initFromStream(new ByteArrayInputStream((baseCfg + "DtnmaRouter.managementMode = M0_STATIC\n").getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp0 = new Settings("Group");
        SimpleBroadcastInterface if0 = new SimpleBroadcastInterface(new Settings("bt"));
        if0.setGroupSettings(grp0);
        MovementModel mm0 = new StationaryMovement(grp0);
        DtnmaRouter pM0 = new DtnmaRouter(grp0);

        Settings.initFromStream(new ByteArrayInputStream((baseCfg + "DtnmaRouter.managementMode = M1_REMOTE_ONLY\n").getBytes(StandardCharsets.UTF_8)));
        Settings grp1 = new Settings("Group");
        DtnmaRouter pM1 = new DtnmaRouter(grp1);

        Settings.initFromStream(new ByteArrayInputStream((baseCfg + "DtnmaRouter.managementMode = M2_AUTONOMIC_SRL\n").getBytes(StandardCharsets.UTF_8)));
        Settings grp2 = new Settings("Group");
        DtnmaRouter pM2 = new DtnmaRouter(grp2);

        DTNHost hMgr = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "mgr", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM2);
        DTNHost hM0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m0", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM0);
        DTNHost hM1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m1", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM1);
        DTNHost hM2 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m2", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM2);

        DtnmaRouter rM0 = (DtnmaRouter) hM0.getRouter();
        DtnmaRouter rM1 = (DtnmaRouter) hM1.getRouter();
        DtnmaRouter rM2 = (DtnmaRouter) hM2.getRouter();

        // Saturate buffers >= 96% -> only M2 should react locally without remote update
        for (DtnmaRouter rr : Arrays.asList(rM0, rM1, rM2)) {
            rr.createNewMessage(new Message(rr.getManagedHost(), hMgr, "CRITICAL_SAT", 96000));
        }
        SimClock.getInstance().advance(2.0);
        hM0.update(false);
        hM1.update(false);
        hM2.update(false);

        boolean localSplitOk = (rM0.getCurrentSprayQuota() == 6)
                && (rM1.getCurrentSprayQuota() == 6)
                && (rM2.getCurrentSprayQuota() <= 2);

        // Remote CTRL_UPDATE_THROTTLE arrives -> mutates M1, ignored by M0
        hMgr.forceConnection(hM1, null, true);
        hMgr.forceConnection(hM0, null, true);
        Message mThrot1 = new Message(hMgr, hM1, "CTRL_UPDATE_THROTTLE_1", 1024);
        Message mThrot0 = new Message(hMgr, hM0, "CTRL_UPDATE_THROTTLE_0", 1024);
        boolean epiFlag = rM1.getDtnmaAgent().isEpidemicControlPriority(mThrot1);
        rM1.getDtnmaAgent().applyDownlinkControlBundle(mThrot1);
        rM0.getDtnmaAgent().applyDownlinkControlBundle(mThrot0);

        boolean throtOk = (rM1.getCurrentSprayQuota() <= 2)
                && (rM1.getDtnmaAgent().getCurrentIngressMode() >= 1)
                && (rM0.getCurrentSprayQuota() == 6);

        // Later CTRL_UPDATE_NOMINAL restores M1 back to nominal L=6
        SimClock.getInstance().advance(10.0);
        Message mNom1 = new Message(hMgr, hM1, "CTRL_UPDATE_NOMINAL_1", 1024);
        rM1.getDtnmaAgent().applyDownlinkControlBundle(mNom1);
        boolean nomOk = (rM1.getCurrentSprayQuota() == 6) && (rM1.getDtnmaAgent().getCurrentIngressMode() == 0);

        check(localSplitOk && epiFlag && throtOk && nomOk,
                "Test15: M0 vs M1 remote-only vs M2 local SRL split, repeated CTRL_UPDATE, & epidemic ctrl flag",
                String.format("localSplit=%s epi=%s throt=%s nom=%s (M1q=%d)",
                        localSplitOk, epiFlag, throtOk, nomOk, rM1.getCurrentSprayQuota()));
    }

    private static void test16DensityGossipBackoffAndOpenLoopAudit() {
        String cfgAdapt =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 100000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 15\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "DtnmaRouter.gossipCooldown = 120.0\n" +
                "DtnmaRouter.densityAdaptiveGossip = true\n" +
                "DtnmaRouter.openLoopAuditOnly = true\n" +
                "DtnmaRouter.staticQuota = 6\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgAdapt.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);
        DtnmaRouter proto = new DtnmaRouter(grp);

        DTNHost h = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "node", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, proto);
        DtnmaRouter r = (DtnmaRouter) h.getRouter();
        r.getDtnmaAgent().ensureSubsystemsInitialized();

        // Simulate 12 unique neighbors seen -> effective cooldown = 120 * (12/4) = 360.0s
        for (int k = 1; k <= 12; k++) {
            DTNHost peer = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                    "p" + k, Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, proto);
            r.getEddSampler().recordContact(peer);
        }
        double effAdaptive = r.getDtnmaAgent().getEffectiveGossipCooldown();
        r.getConfig().densityAdaptiveGossip = false;
        double effFixed = r.getDtnmaAgent().getEffectiveGossipCooldown();
        boolean densityOk = Math.abs(effAdaptive - 360.0) < 1e-6 && Math.abs(effFixed - 120.0) < 1e-6;

        // Under openLoopAuditOnly=true with Q=0.96, audit records intervention (profile=0), while quota stays 6
        r.createNewMessage(new Message(h, h, "CRITICAL_AUDIT", 96000));
        SimClock.getInstance().advance(2.0);
        h.update(false);
        boolean openLoopOk = r.getCurrentSprayQuota() == 6 && r.getLastExecutedProfile() == 0;

        check(densityOk && openLoopOk,
                "Test16: Density-adaptive gossip cooldown scales to 360s at D=12 & openLoopAuditOnly leaves L=6 unmutated",
                String.format("effAdaptive=%.1f effFixed=%.1f quota=%d execProf=%d",
                        effAdaptive, effFixed, r.getCurrentSprayQuota(), r.getLastExecutedProfile()));
    }

    private static void test17StaticOracleFamilyL8L12() {
        String cfgText =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 3\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);

        DtnmaStaticAckL8Router p8 = new DtnmaStaticAckL8Router(grp);
        DtnmaStaticAckL12Router p12 = new DtnmaStaticAckL12Router(grp);
        DTNHost h8 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "h8", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p8);
        DTNHost h12 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "h12", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p12);

        SimClock.getInstance().advance(2.0);
        h8.update(false);
        h12.update(false);
        DtnmaRouter r8 = (DtnmaRouter) h8.getRouter();
        DtnmaRouter r12 = (DtnmaRouter) h12.getRouter();

        boolean ok = r8.getCurrentSprayQuota() == 8 && r12.getCurrentSprayQuota() == 12
                && r8.getIntelligenceMode() == DtnmaRouter.IntelligenceMode.STATIC_RULES
                && r12.getIntelligenceMode() == DtnmaRouter.IntelligenceMode.STATIC_RULES;
        check(ok,
                "Test17: Static oracle presets DtnmaStaticAckL8Router & DtnmaStaticAckL12Router load L=8,12 cleanly",
                String.format("L8=%d L12=%d", r8.getCurrentSprayQuota(), r12.getCurrentSprayQuota()));
    }

    private static void test18InterventionTiersPresetsAndCatchAllRule() {
        // 1. Verify updated rules_vdtn.txt catch-all release on steady Q=0.90, dQ=0.0, CritQ=0.05
        String rulePath = new File("scenarios/rules_vdtn.txt").isFile()
                ? "scenarios/rules_vdtn.txt" : "../ADTNM/scenarios/rules_vdtn.txt";
        boolean rulesOk = false;
        String ruleInfo = "missing";
        try {
            List<StateBasedRule> rules = DtnmaRuleParser.parseFile(rulePath);
            StubSampler s = new StubSampler();
            s.q = 0.90; s.critQ = 0.05; s.drift = 0.0; s.soc = 0.80; s.dens = 2.0;
            StateBasedRule firedSteady = null;
            for (StateBasedRule r : rules) {
                if (r.isTriggered(s)) { firedSteady = r; break; }
            }
            s.q = 0.90; s.drift = 0.010;
            StateBasedRule firedSurge = null;
            for (StateBasedRule r : rules) {
                if (r.isTriggered(s)) { firedSurge = r; break; }
            }
            s.q = 0.40; s.drift = 0.0; s.soc = 0.15;
            StateBasedRule firedLowSoc = null;
            for (StateBasedRule r : rules) {
                if (r.isTriggered(s)) { firedLowSoc = r; break; }
            }
            rulesOk = (firedSteady != null && "ARI://DTNMA/SRL/VDTN_URBAN_NOMINAL".equals(firedSteady.getAri()))
                    && (firedSurge != null && firedSurge.getPriority() == 80)
                    && (firedLowSoc != null && firedLowSoc.getPriority() == 100);
            ruleInfo = String.format("steady=%s surge=%s lowSoc=%s",
                    firedSteady != null ? firedSteady.getAri() : "null",
                    firedSurge != null ? firedSurge.getAri() : "null",
                    firedLowSoc != null ? firedLowSoc.getAri() : "null");
        } catch (Exception e) {
            ruleInfo = e.getMessage();
        }

        // 2. Verify M0b/M1/M2/M3 presets & InterventionTier 1 vs Tier 3
        String baseCfg =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 5\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n" +
                "DtnmaRouter.staticQuota = 6\n";
        Settings.initFromStream(new ByteArrayInputStream(baseCfg.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);

        DtnmaM0bStaticRouter p0b = new DtnmaM0bStaticRouter(grp);
        DtnmaM1RemoteRouter p1 = new DtnmaM1RemoteRouter(grp);
        DtnmaM2SrlRouter p2 = new DtnmaM2SrlRouter(grp);
        DtnmaM3RlRouter p3 = new DtnmaM3RlRouter(grp);

        DTNHost h0b = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m0b", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p0b);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m1", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p1);
        DTNHost h2 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m2", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p2);
        DTNHost h3 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m3", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p3);

        boolean presetsOk = ((DtnmaRouter) h0b.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M0_STATIC
                && !((DtnmaRouter) h0b.getRouter()).getConfig().epidemicControlFlooding
                && ((DtnmaRouter) h1.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY
                && !((DtnmaRouter) h1.getRouter()).getConfig().epidemicControlFlooding
                && ((DtnmaRouter) h2.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL
                && !((DtnmaRouter) h2.getRouter()).isGossipEnabled()
                && ((DtnmaRouter) h3.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M3_AUTONOMIC_RL
                && ((DtnmaRouter) h3.getRouter()).isGossipEnabled();

        Settings.initFromStream(new ByteArrayInputStream(
                (baseCfg + "DtnmaRouter.interventionTier = TIER1_PROTECTION_ONLY\n").getBytes(StandardCharsets.UTF_8)));
        ManagedSprayAndWaitRouter pmTier1 = new ManagedSprayAndWaitRouter(new Settings("Group"));
        pmTier1.getDtnmaAgent().setDynamicSprayQuota(1);
        pmTier1.getDtnmaAgent().setIngressMode(DtnmaRouter.INGRESS_CRITICAL_ONLY);
        boolean tier1Ok = pmTier1.getDtnmaAgent().getInterventionTier() == DtnmaConfig.InterventionTier.TIER1_PROTECTION_ONLY
                && pmTier1.getDtnmaAgent().getEffectiveBulkSprayQuota(6) == 6
                && !pmTier1.getDtnmaAgent().isCriticalOnlyBulkSheddingActive()
                && pmTier1.getDtnmaAgent().allowNonDirectBulkForward(
                        new Message(h0b, h1, "BULK_T1", 1024), 0.90, 0.96, 0.10, true, 0.1, 0.2);

        check(rulesOk && presetsOk && tier1Ok,
                "Test18: InterventionTier 1/3, M0b..M3 presets, & rules_vdtn catch-all release work correctly",
                String.format("rules=%s(%s) presets=%s tier1=%s", rulesOk, ruleInfo, presetsOk, tier1Ok));
    }

    private static void test19M0cM0dM1FloodEquivalenceAndEnergyAware() {
        String baseCfg =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 100000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 6\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "DtnmaRouter.equivalenceConnOuterOrder = true\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n";
        Settings.initFromStream(new ByteArrayInputStream(baseCfg.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);

        DtnmaM0cV1GuardRouter pm0c = new DtnmaM0cV1GuardRouter(grp);
        DtnmaM0dConservativeRouter pm0d = new DtnmaM0dConservativeRouter(grp);
        DtnmaM1FloodRouter pm1f = new DtnmaM1FloodRouter(grp);
        EnergyAwareEpidemicRouter peae = new EnergyAwareEpidemicRouter(grp);

        DTNHost h0c = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m0c", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, pm0c);
        DTNHost h0d = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m0d", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, pm0d);
        DTNHost h1f = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m1f", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, pm1f);
        DTNHost heae = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "eae", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, peae);

        // Verify M0c fires pre-installed V1 guard at Q >= 0.88 even without CritQ and ignores remote CTRL_UPDATE
        DtnmaM0cV1GuardRouter r0c = (DtnmaM0cV1GuardRouter) h0c.getRouter();
        r0c.createNewMessage(new Message(h0c, h0d, "BULK_M0C", 90000));
        SimClock.getInstance().advance(2.0);
        h0c.update(false);
        r0c.getDtnmaAgent().applyDownlinkControlBundle(new Message(h1f, h0c, "CTRL_UPDATE_THROTTLE_1", 1024));
        boolean m0cOk = !r0c.supportsDynamicControlReconfig()
                && r0c.getCurrentSprayQuota() == 1
                && r0c.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY;

        // Verify M0d boots conservative P1 (L=2, INGRESS=1) and M1-F sets epidemicControlFlooding=true
        DtnmaM0dConservativeRouter r0d = (DtnmaM0dConservativeRouter) h0d.getRouter();
        DtnmaM1FloodRouter r1f = (DtnmaM1FloodRouter) h1f.getRouter();
        boolean m0dM1fOk = r0d.getCurrentSprayQuota() == 2
                && r0d.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_THROTTLE_BULK
                && r1f.getConfig().epidemicControlFlooding
                && r1f.getConfig().equivalenceConnOuterOrder;

        // Verify EnergyAwareEpidemicRouter protects critical messages while evicting bulk under full buffer
        EnergyAwareEpidemicRouter rEae = (EnergyAwareEpidemicRouter) heae.getRouter();
        boolean cOk = rEae.createNewMessage(new Message(heae, h0d, "CRITICAL_1", 40000));
        boolean b1Ok = rEae.createNewMessage(new Message(heae, h0d, "BULK_1", 55000));
        boolean b2Ok = rEae.createNewMessage(new Message(heae, h0d, "BULK_2", 30000));
        boolean eaeProtected = cOk && b1Ok && b2Ok
                && rEae.hasMessage("CRITICAL_1")
                && !rEae.hasMessage("BULK_1")
                && rEae.hasMessage("BULK_2");

        check(m0cOk && m0dM1fOk && eaeProtected,
                "Test19: M0c V1-guard/immutable, M0d/M1-F presets, ordering flag, & EnergyAwareEpidemic work cleanly",
                String.format("m0c=%s(q=%d,ing=%d) m0dM1f=%s eae=%s",
                        m0cOk, r0c.getCurrentSprayQuota(), r0c.getDtnmaAgent().getCurrentIngressMode(),
                        m0dM1fOk, eaeProtected));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static void test20NominalBackpressureAndAckEvictionSplit() {
        String rulePath = new File("scenarios/rules_vdtn.txt").isFile()
                ? "scenarios/rules_vdtn.txt" : "../ADTNM/scenarios/rules_vdtn.txt";

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("dtnma_test20_");
            String repDir = tmpDir.toAbsolutePath().toString().replace('\\', '/');

            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();
            String cfg =
                    "Scenario.name = Test20Scenario\n" +
                    "Scenario.simulateConnections = true\n" +
                    "Scenario.updateInterval = 1.0\n" +
                    "Scenario.endTime = 200\n" +
                    "Scenario.nrofHostGroups = 3\n" +
                    "MovementModel.worldSize = 100, 100\n" +
                    "MovementModel.warmup = 0\n" +
                    "MovementModel.rngSeed = 1\n" +
                    "Events.nrof = 0\n" +
                    "bt.type = SimpleBroadcastInterface\n" +
                    "bt.transmitSpeed = 250000\n" +
                    "bt.transmitRange = 100\n" +
                    "Group.movementModel = StationaryMovement\n" +
                    "Group.bufferSize = 500000\n" +
                    "Group.msgTtl = 300\n" +
                    "Group.speed = 1.0, 1.0\n" +
                    "Group.waitTime = 0, 0\n" +
                    "Group.nodeLocation = 0, 0\n" +
                    "Group.nrofInterfaces = 1\n" +
                    "Group.interface1 = bt\n" +
                    "SprayAndWaitRouter.nrofCopies = 6\n" +
                    "SprayAndWaitRouter.binaryMode = true\n" +
                    "DtnmaRouter.rulesFile = " + rulePath.replace('\\', '/') + "\n" +
                    "Group1.groupID = m\n" +
                    "Group1.nrofHosts = 1\n" +
                    "Group1.router = DtnmaM2SrlRouter\n" +
                    "Group1.nodeLocation = 500, 500\n" +
                    "Group2.groupID = e\n" +
                    "Group2.nrofHosts = 2\n" +
                    "Group2.router = EnergyAwareEpidemicRouter\n" +
                    "Group2.nodeLocation = 0, 0\n" +
                    "Group3.groupID = d\n" +
                    "Group3.nrofHosts = 1\n" +
                    "Group3.router = ManagedEpidemicRouter\n" +
                    "Group3.nodeLocation = 500, 500\n" +
                    "Report.warmup = 0\n" +
                    "Report.reportDir = " + repDir + "\n" +
                    "DtnmaRlMetricsReport.eventA = 0, 100\n" +
                    "DtnmaRlMetricsReport.eventHorizon = 100\n";
            Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
            SimClock.reset();

            List<DTNHost> hosts = SimScenario.getInstance().getHosts();
            DTNHost hM2 = hosts.get(0);
            DTNHost hEaeA = hosts.get(1);
            DTNHost hEaeB = hosts.get(2);
            DTNHost hDst = hosts.get(3);

            // 1. Check M2 with rules_vdtn.txt nominal backpressure == true (P3)
            DtnmaM2SrlRouter rM2 = (DtnmaM2SrlRouter) hM2.getRouter();
            rM2.getDtnmaAgent().loadRulesFromFile(rulePath);
            SimClock.getInstance().advance(2.0);
            hM2.update(false);
            boolean vdtnBpOk = rM2.getDtnmaAgent().isBackpressureEnabled() && rM2.getLastExecutedProfile() == 3;

            // 2. Check EnergyAwareEpidemicRouter nominal backpressure holds bulk at peerQ=0.80 while sending critical
            EnergyAwareEpidemicRouter rEaeA = (EnergyAwareEpidemicRouter) hEaeA.getRouter();
            EnergyAwareEpidemicRouter rEaeB = (EnergyAwareEpidemicRouter) hEaeB.getRouter();
            rEaeB.createNewMessage(new Message(hEaeB, hDst, "BULK_FILL_400K", 400000));
            rEaeA.createNewMessage(new Message(hEaeA, hDst, "BULK_BP_HOLD", 1000));
            hEaeA.forceConnection(hEaeB, null, true);
            SimClock.getInstance().advance(2.0);
            hEaeA.update(true);
            boolean eaeHoldBulk = !rEaeA.isTransferring();
            rEaeA.createNewMessage(new Message(hEaeA, hDst, "CRITICAL_BP_BYPASS", 1000));
            SimClock.getInstance().advance(2.0);
            hEaeA.update(true);
            boolean eaeAllowCrit = rEaeA.isSending("CRITICAL_BP_BYPASS") && !rEaeA.isSending("BULK_BP_HOLD");
            hEaeA.forceConnection(hEaeB, null, false);

            // 3. Verify isHostKnownAcked + DtnmaRlMetricsReport ACKed vs un-ACKed eviction split
            SimClock.getInstance().setTime(10.0);
            DtnmaRlMetricsReport rep = new DtnmaRlMetricsReport();
            Message mAcked = new Message(hDst, hEaeB, "CRITICAL_ACKED_20", 1000);
            Message mUnacked = new Message(hEaeB, hDst, "CRITICAL_UNACKED_20", 1000);
            ManagedEpidemicRouter rDtnma = (ManagedEpidemicRouter) hDst.getRouter();
            rDtnma.getDtnmaAgent().ensureSubsystemsInitialized();

            boolean beforeAckFalse = !DtnmaRlMetricsReport.isHostKnownAcked(hDst, mAcked)
                    && !DtnmaRlMetricsReport.isHostKnownAcked(hEaeB, mAcked);
            rep.newMessage(mAcked);
            rep.newMessage(mUnacked);

            SimClock.getInstance().setTime(20.0);
            rDtnma.getDtnmaAgent().getDeliveredAckSet().put(mAcked.getId(), Boolean.TRUE);
            rEaeB.receiveMessage(mAcked, hDst);
            rEaeB.messageTransferred(mAcked.getId(), hDst);
            boolean afterAckTrue = DtnmaRlMetricsReport.isHostKnownAcked(hDst, mAcked)
                    && DtnmaRlMetricsReport.isHostKnownAcked(hEaeB, mAcked)
                    && !DtnmaRlMetricsReport.isHostKnownAcked(hEaeB, mUnacked);

            rep.messageTransferred(mAcked, hDst, hEaeB, true);
            SimClock.getInstance().setTime(30.0);
            rep.messageDeleted(mAcked, hDst, true);
            rep.messageDeleted(mUnacked, hEaeB, true);

            SimClock.getInstance().setTime(50.0);
            rep.done();

            Path outFile = tmpDir.resolve("Test20Scenario_DtnmaRlMetricsReport.txt");
            Map<String, String> kv = new HashMap<String, String>();
            for (String line : Files.readAllLines(outFile, StandardCharsets.UTF_8)) {
                int idx = line.indexOf(": ");
                if (idx > 0) {
                    kv.put(line.substring(0, idx).trim(), line.substring(idx + 2).trim());
                }
            }
            boolean reportSplitOk = "1".equals(kv.get("critical_buffer_dropped"))
                    && "1".equals(kv.get("critical_buffer_dropped_acked"))
                    && "1".equals(kv.get("unique_critical_lost"))
                    && "1".equals(kv.get("eventA_crit_evicted"))
                    && "1".equals(kv.get("eventA_crit_evicted_acked"))
                    && "1".equals(kv.get("eventA_crit_unique_lost"));

            check(vdtnBpOk && eaeHoldBulk && eaeAllowCrit && beforeAckFalse && afterAckTrue && reportSplitOk,
                    "Test20: Nominal backpressure (P3), isHostKnownAcked, & ACKed critical eviction split work cleanly",
                    String.format("vdtnBp=%s eaeHold=%s eaeCrit=%s ackPre=%s ackPost=%s split=%s(cbd=%s,cbda=%s,ev=%s,eva=%s,ul=%s)",
                            vdtnBpOk, eaeHoldBulk, eaeAllowCrit, beforeAckFalse, afterAckTrue, reportSplitOk,
                            kv.get("critical_buffer_dropped"), kv.get("critical_buffer_dropped_acked"),
                            kv.get("eventA_crit_evicted"), kv.get("eventA_crit_evicted_acked"),
                            kv.get("eventA_crit_unique_lost")));
        } catch (Exception e) {
            check(false, "Test20: Nominal backpressure (P3), isHostKnownAcked, & ACKed critical eviction split",
                    "Exception: " + e.toString());
        } finally {
            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();
            if (tmpDir != null) {
                deleteRecursive(tmpDir.toFile());
            }
        }
    }

    private static void test21EnergyOnlyGuardsAndGroupControlBroadcast() {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("dtnma_test21_");
            String repDir = tmpDir.toAbsolutePath().toString().replace('\\', '/');

            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();

            String cfg =
                    "Scenario.name = Test21Scenario\n" +
                    "Scenario.simulateConnections = false\n" +
                    "Scenario.updateInterval = 1.0\n" +
                    "Scenario.endTime = 200\n" +
                    "Scenario.nrofHostGroups = 4\n" +
                    "MovementModel.worldSize = 1000, 1000\n" +
                    "MovementModel.warmup = 0\n" +
                    "MovementModel.rngSeed = 1\n" +
                    "Events.nrof = 0\n" +
                    "bt.type = SimpleBroadcastInterface\n" +
                    "bt.transmitSpeed = 250000\n" +
                    "bt.transmitRange = 35\n" +
                    "Group.movementModel = StationaryMovement\n" +
                    "Group.bufferSize = 100000\n" +
                    "Group.msgTtl = 300\n" +
                    "Group.speed = 1.0, 1.0\n" +
                    "Group.waitTime = 0, 0\n" +
                    "Group.nrofInterfaces = 1\n" +
                    "Group.interface1 = bt\n" +
                    "Group.initialEnergy = 100.0\n" +
                    "Group.scanEnergy = 0.0\n" +
                    "Group.scanResponseEnergy = 0.0\n" +
                    "Group.transmitEnergy = 0.0\n" +
                    "Group.energyWarmup = 0\n" +
                    "SprayAndWaitRouter.nrofCopies = 6\n" +
                    "SprayAndWaitRouter.binaryMode = true\n" +
                    "Group1.groupID = p\n" +
                    "Group1.nrofHosts = 1\n" +
                    "Group1.router = DtnmaM0eEnergy20Router\n" +
                    "Group1.nodeLocation = 500, 500\n" +
                    "Group2.groupID = q\n" +
                    "Group2.nrofHosts = 1\n" +
                    "Group2.router = DtnmaM0eEnergy25Router\n" +
                    "Group2.nodeLocation = 500, 500\n" +
                    "Group3.groupID = f\n" +
                    "Group3.nrofHosts = 1\n" +
                    "Group3.router = DtnmaM1FloodGroupRouter\n" +
                    "Group3.nodeLocation = 0, 0\n" +
                    "Group4.groupID = mgr\n" +
                    "Group4.nrofHosts = 2\n" +
                    "Group4.router = DtnmaM1FloodGroupRouter\n" +
                    "Group4.nodeLocation = 0, 0\n" +
                    "Report.warmup = 0\n" +
                    "Report.reportDir = " + repDir + "\n";
            Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
            SimClock.reset();

            List<DTNHost> hosts = SimScenario.getInstance().getHosts();
            DTNHost h20 = hosts.get(0);
            DTNHost h25 = hosts.get(1);
            DTNHost hFld = hosts.get(2);
            DTNHost hMgr0 = hosts.get(3);
            DTNHost hMgr1 = hosts.get(4);

            DtnmaM0eEnergy20Router r20 = (DtnmaM0eEnergy20Router) h20.getRouter();
            DtnmaM0eEnergy25Router r25 = (DtnmaM0eEnergy25Router) h25.getRouter();
            DtnmaM1FloodGroupRouter rFld = (DtnmaM1FloodGroupRouter) hFld.getRouter();

            // 1. Both M0e-20 and M0e-25 stay nominal at Q=0.95, SoC=1.0 and ignore remote CTRL_UPDATE
            r20.createNewMessage(new Message(h20, hMgr0, "BULK_FULL_20", 95000));
            r25.createNewMessage(new Message(h25, hMgr0, "BULK_FULL_25", 95000));
            SimClock.getInstance().advance(2.0);
            h20.update(false);
            h25.update(false);
            r20.getDtnmaAgent().applyDownlinkControlBundle(new Message(hMgr0, h20, "CTRL_UPDATE_THROTTLE_1", 1024));
            r25.getDtnmaAgent().applyDownlinkControlBundle(new Message(hMgr0, h25, "CTRL_UPDATE_THROTTLE_2", 1024));
            boolean nomHighQOk = !r20.supportsDynamicControlReconfig()
                    && !r25.supportsDynamicControlReconfig()
                    && r20.getCurrentSprayQuota() == 6
                    && r20.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL
                    && r25.getCurrentSprayQuota() == 6
                    && r25.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL;

            // 2. At SoC=0.22, M0e-25 triggers P0 while M0e-20 stays nominal; at SoC=0.18, M0e-20 triggers P0
            h20.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 22.0);
            h25.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 22.0);
            SimClock.getInstance().advance(2.0);
            h20.update(false);
            h25.update(false);
            boolean split022Ok = r20.getCurrentSprayQuota() == 6
                    && r20.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL
                    && r25.getCurrentSprayQuota() == 1
                    && r25.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY;

            h20.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 18.0);
            SimClock.getInstance().advance(2.0);
            h20.update(false);
            boolean trig018Ok = r20.getCurrentSprayQuota() == 1
                    && r20.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY;

            // 3. M1-F-Group adopts CTRL_GROUP_* on relay receipt without ACK pruning + report counts targets
            SimClock.getInstance().setTime(10.0);
            DtnmaRlMetricsReport rep = new DtnmaRlMetricsReport();
            Message gThrot = new Message(hMgr0, hMgr1, "CTRL_GROUP_THROTTLE_W1_1", 4096);
            boolean clsOk = DtnmaRouter.isGroupControlMessage(gThrot)
                    && DtnmaRouter.isDownlinkControlMessage(gThrot)
                    && !DtnmaRouter.isTelemetryOrAlarmMessage(gThrot);
            rep.newMessage(gThrot);

            hFld.update(false);
            SimClock.getInstance().setTime(15.0);
            rFld.receiveMessage(gThrot, hMgr0);
            rFld.messageTransferred(gThrot.getId(), hMgr0);
            rep.messageTransferred(gThrot, hMgr0, hFld, false);
            boolean grpThrotOk = rFld.getCurrentSprayQuota() == 2
                    && rFld.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_THROTTLE_BULK
                    && !rFld.getDtnmaAgent().isAcked(gThrot);

            SimClock.getInstance().setTime(20.0);
            Message gNom = new Message(hMgr0, hMgr1, "CTRL_GROUP_NOMINAL_W1_1", 4096);
            rFld.receiveMessage(gNom, hMgr0);
            rFld.messageTransferred(gNom.getId(), hMgr0);
            boolean grpNomOk = rFld.getCurrentSprayQuota() == 6
                    && rFld.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL
                    && !rFld.getDtnmaAgent().isAcked(gNom);

            rep.done();
            Path outFile = tmpDir.resolve("Test21Scenario_DtnmaRlMetricsReport.txt");
            Map<String, String> kv = new HashMap<String, String>();
            for (String line : Files.readAllLines(outFile, StandardCharsets.UTF_8)) {
                int idx = line.indexOf(": ");
                if (idx > 0) {
                    kv.put(line.substring(0, idx).trim(), line.substring(idx + 2).trim());
                }
            }
            boolean repOk = "3".equals(kv.get("downlink_created")) && "1".equals(kv.get("downlink_delivered"));

            check(nomHighQOk && split022Ok && trig018Ok && clsOk && grpThrotOk && grpNomOk && repOk,
                    "Test21: M0e-20/M0e-25 energy-only guards & M1-F-Group broadcast adoption work cleanly",
                    String.format("nom=%s split022=%s trig018=%s cls=%s throt=%s nomGrp=%s rep=%s(dc=%s,dd=%s)",
                            nomHighQOk, split022Ok, trig018Ok, clsOk, grpThrotOk, grpNomOk, repOk,
                            kv.get("downlink_created"), kv.get("downlink_delivered")));
        } catch (Exception e) {
            e.printStackTrace();
            check(false, "Test21: M0e-20/M0e-25 energy-only guards & M1-F-Group broadcast adoption",
                    "Exception: " + e.toString());
        } finally {
            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();
            if (tmpDir != null) {
                deleteRecursive(tmpDir.toFile());
            }
        }
    }

    private static void test22M2LocalOnlyIgnoresRemoteUpdates() {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("dtnma_test22_");
            String repDir = tmpDir.toAbsolutePath().toString().replace('\\', '/');
            String rulePath = "n:/R&D/cog/ADTNM/scenarios/rules_vdtn.txt";

            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();

            String cfg =
                    "Scenario.name = Test22Scenario\n" +
                    "Scenario.simulateConnections = false\n" +
                    "Scenario.updateInterval = 1.0\n" +
                    "Scenario.endTime = 200\n" +
                    "Scenario.nrofHostGroups = 2\n" +
                    "MovementModel.worldSize = 1000, 1000\n" +
                    "MovementModel.warmup = 0\n" +
                    "MovementModel.rngSeed = 1\n" +
                    "Events.nrof = 0\n" +
                    "bt.type = SimpleBroadcastInterface\n" +
                    "bt.transmitSpeed = 250000\n" +
                    "bt.transmitRange = 35\n" +
                    "Group.movementModel = StationaryMovement\n" +
                    "Group.bufferSize = 100000\n" +
                    "Group.msgTtl = 300\n" +
                    "Group.speed = 1.0, 1.0\n" +
                    "Group.waitTime = 0, 0\n" +
                    "Group.nrofInterfaces = 1\n" +
                    "Group.interface1 = bt\n" +
                    "Group.initialEnergy = 100.0\n" +
                    "Group.scanEnergy = 0.0\n" +
                    "Group.scanResponseEnergy = 0.0\n" +
                    "Group.transmitEnergy = 0.0\n" +
                    "Group.energyWarmup = 0\n" +
                    "Group.rulesFile = " + rulePath + "\n" +
                    "DtnmaRouter.rulesFile = " + rulePath + "\n" +
                    "SprayAndWaitRouter.nrofCopies = 6\n" +
                    "SprayAndWaitRouter.binaryMode = true\n" +
                    "Group1.groupID = p\n" +
                    "Group1.nrofHosts = 1\n" +
                    "Group1.router = DtnmaM2LocalOnlyRouter\n" +
                    "Group1.nodeLocation = 500, 500\n" +
                    "Group2.groupID = mgr\n" +
                    "Group2.nrofHosts = 1\n" +
                    "Group2.router = DtnmaM1RemoteRouter\n" +
                    "Group2.nodeLocation = 0, 0\n" +
                    "Report.warmup = 0\n" +
                    "Report.reportDir = " + repDir + "\n";
            Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
            SimClock.reset();

            List<DTNHost> hosts = SimScenario.getInstance().getHosts();
            DTNHost hLoc = hosts.get(0);
            DTNHost hMgr = hosts.get(1);
            DtnmaM2LocalOnlyRouter rLoc = (DtnmaM2LocalOnlyRouter) hLoc.getRouter();
            rLoc.getDtnmaAgent().loadRulesFromFile(rulePath);

            boolean noReconfig = !rLoc.supportsDynamicControlReconfig();

            SimClock.getInstance().advance(2.0);
            hLoc.update(false);
            rLoc.getDtnmaAgent().applyDownlinkControlBundle(new Message(hMgr, hLoc, "CTRL_UPDATE_V1_1", 1024));
            rLoc.getDtnmaAgent().applyDownlinkControlBundle(new Message(hMgr, hLoc, "CTRL_UPDATE_THROTTLE_W1_0", 1024));

            boolean energyFloorPreserved = Math.abs(rLoc.getDtnmaAgent().getConfig().shieldEnergyMin - 0.20) < 1e-9;
            boolean noDownlinkOverlay = true;
            for (StateBasedRule r : rLoc.getDtnmaAgent().getRuleDatabase()) {
                if (r.getAri() != null && r.getAri().startsWith("ARI://DTNMA/SRL/DOWNLINK_")) {
                    noDownlinkOverlay = false;
                }
            }
            boolean stillNominalAt100 = rLoc.getCurrentSprayQuota() == 6
                    && rLoc.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL;

            hLoc.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 18.0);
            SimClock.getInstance().advance(2.0);
            hLoc.update(false);
            boolean triggersAt018 = rLoc.getCurrentSprayQuota() == 1
                    && rLoc.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY;

            hLoc.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 100.0);
            SimClock.getInstance().advance(2.0);
            hLoc.update(false);
            boolean releasesToNominal = rLoc.getCurrentSprayQuota() == 6
                    && rLoc.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL;

            check(noReconfig && energyFloorPreserved && noDownlinkOverlay && stillNominalAt100 && triggersAt018 && releasesToNominal,
                    "Test22: M2-LocalOnly ignores remote CTRL_UPDATE overlays while preserving local rules",
                    String.format("noRec=%s floor=%s noOv=%s nom100=%s trig018=%s rel=%s",
                            noReconfig, energyFloorPreserved, noDownlinkOverlay, stillNominalAt100, triggersAt018, releasesToNominal));
        } catch (Exception e) {
            e.printStackTrace();
            check(false, "Test22: M2-LocalOnly ignores remote CTRL_UPDATE overlays",
                    "Exception: " + e.toString());
        } finally {
            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();
            if (tmpDir != null) {
                deleteRecursive(tmpDir.toFile());
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("ADTNM SINGLE-CRITIC / PHASE 2 JAVA UNIT TEST SUITE");
        System.out.println("=================================================================");
        test1SafetyShieldInvariantsAndLiveness();
        test2CounterfactualShapingAndLocalization();
        test3SymmetricAtomicGossipConsensus();
        test4CausalDelayedAckEligibilityCredit();
        test5BeaconQuantizationAndWireSizes();
        test6BinaryTokenConservationAtRelay();
        test7AllCriticalFullBufferZeroOvershoot();
        test9ProtectedFifoEvictionAndCriticalOccupancy();
        test10PriorityMaxPropAndTunedStatic();
        test11ComponentLadderAndDownlinkControl();
        test12DownlinkInBandControlAdoption();
        test13ComposedStockRoutersProtection();
        test14PolicyEquivalenceAndRuntimeRules();
        test15ManagementParadigmsM0M1M2();
        test16DensityGossipBackoffAndOpenLoopAudit();
        test17StaticOracleFamilyL8L12();
        test18InterventionTiersPresetsAndCatchAllRule();
        test19M0cM0dM1FloodEquivalenceAndEnergyAware();
        test20NominalBackpressureAndAckEvictionSplit();
        test21EnergyOnlyGuardsAndGroupControlBroadcast();
        test22M2LocalOnlyIgnoresRemoteUpdates();
        System.out.println("=================================================================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed.");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
