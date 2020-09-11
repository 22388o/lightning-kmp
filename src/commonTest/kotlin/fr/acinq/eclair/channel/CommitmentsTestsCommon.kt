package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.CommitmentSpecTestsCommon
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitmentsTests {
    val logger = LoggerFactory.default.newLogger(Logger.Tag(CommitmentSpecTestsCommon::class))

    @Test
    fun `reach normal state`() {
        reachNormal()
    }

    @Test
    fun `take additional HTLC fee into account`() {
        val (alice, bob) = reachNormal()
        // The fee for a single HTLC is 1720000 msat but the funder keeps an extra reserve to make sure we're able to handle
        // an additional HTLC at twice the feerate (hence the multiplier).
        val htlcOutputFee = (3 * 1720000).msat
        val a = 772760000.msat // initial balance alice
        val ac0 = alice.commitments
        val bc0 = bob.commitments
        // we need to take the additional HTLC fee into account because balances are above the trim threshold.
        assertEquals(ac0.availableBalanceForSend(), a - htlcOutputFee)
        assertEquals(bc0.availableBalanceForReceive(), a - htlcOutputFee)

        val currentBlockHeight = 144L
        val cmdAdd = TestsHelper.makeCmdAdd(a - htlcOutputFee - 1000.msat, bob.staticParams.nodeParams.nodeId, currentBlockHeight).second
        val (ac1, add) = ac0.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        val bc1 = bc0.receiveAdd(add).get()
        val (_, commit1) = ac1.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        val (bc2, _) = bc1.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac1.availableBalanceForSend(), 1000.msat)
        assertEquals(bc2.availableBalanceForReceive(), 1000.msat)
    }

    @Test
    fun `correct values for availableForSend - availableForReceive (success case)`() {
        val (alice, bob) = reachNormal()

        val fee = 1720000.msat // fee due to the additional htlc output
        val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase
        val a = (772760000.msat) - fee - funderFeeReserve // initial balance alice
        val b = 190000000.msat // initial balance bob
        val p = 42000000.msat // a->b payment

        val ac0 = alice.commitments
        val bc0 = bob.commitments

        assertTrue(ac0.availableBalanceForSend() > p) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L
        val (payment_preimage, cmdAdd) = TestsHelper.makeCmdAdd(p, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add) = ac0.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        assertEquals(ac1.availableBalanceForSend(), a - p - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val bc1 = bc0.receiveAdd(add).get()
        assertEquals(bc1.availableBalanceForSend(), b)
        assertEquals(bc1.availableBalanceForReceive(), a - p - fee)

        val (ac2, commit1) = ac1.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac2.availableBalanceForSend(), a - p - fee)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (bc2, revocation1) = bc1.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc2.availableBalanceForSend(), b)
        assertEquals(bc2.availableBalanceForReceive(), a - p - fee)

        val ac3 = ac2.receiveRevocation(revocation1).get().first
        assertEquals(ac3.availableBalanceForSend(), a - p - fee)
        assertEquals(ac3.availableBalanceForReceive(), b)

        val (bc3, commit2) = bc2.sendCommit(bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc3.availableBalanceForSend(), b)
        assertEquals(bc3.availableBalanceForReceive(), a - p - fee)

        val (ac4, revocation2) = ac3.receiveCommit(commit2, alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac4.availableBalanceForSend(), a - p - fee)
        assertEquals(ac4.availableBalanceForReceive(), b)

        val bc4 = bc3.receiveRevocation(revocation2).get().first
        assertEquals(bc4.availableBalanceForSend(), b)
        assertEquals(bc4.availableBalanceForReceive(), a - p - fee)

        val cmdFulfill = CMD_FULFILL_HTLC(0, payment_preimage)
        val (bc5, fulfill) = bc4.sendFulfill(cmdFulfill).get()
        assertEquals(bc5.availableBalanceForSend(), b + p) // as soon as we have the fulfill, the balance increases
        assertEquals(bc5.availableBalanceForReceive(), a - p - fee)

        val ac5 = ac4.receiveFulfill(fulfill).get().first
        assertEquals(ac5.availableBalanceForSend(), a - p - fee)
        assertEquals(ac5.availableBalanceForReceive(), b + p)

        val (bc6, commit3) = bc5.sendCommit(bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc6.availableBalanceForSend(), b + p)
        assertEquals(bc6.availableBalanceForReceive(), a - p - fee)

        val (ac6, revocation3) = ac5.receiveCommit(commit3, alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac6.availableBalanceForSend(), a - p)
        assertEquals(ac6.availableBalanceForReceive(), b + p)

        val bc7 = bc6.receiveRevocation(revocation3).get().first
        assertEquals(bc7.availableBalanceForSend(), b + p)
        assertEquals(bc7.availableBalanceForReceive(), a - p)

        val (ac7, commit4) = ac6.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac7.availableBalanceForSend(), a - p)
        assertEquals(ac7.availableBalanceForReceive(), b + p)

        val (bc8, revocation4) = bc7.receiveCommit(commit4, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc8.availableBalanceForSend(), b + p)
        assertEquals(bc8.availableBalanceForReceive(), a - p)

        val ac8 = ac7.receiveRevocation(revocation4).get().first
        assertEquals(ac8.availableBalanceForSend(), a - p)
        assertEquals(ac8.availableBalanceForReceive(), b + p)
    }

    @Test
    fun `correct values for availableForSend - availableForReceive (failure case)`() {
        val (alice, bob) = reachNormal()

        val fee = 1720000.msat // fee due to the additional htlc output
        val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase
        val a = (772760000.msat) - fee - funderFeeReserve // initial balance alice
        val b = 190000000.msat // initial balance bob
        val p = 42000000.msat // a->b payment

        val ac0 = alice.commitments
        val bc0 = bob.commitments

        assertTrue(ac0.availableBalanceForSend() > p) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(p, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add) = ac0.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        assertEquals(ac1.availableBalanceForSend(), a - p - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val bc1 = bc0.receiveAdd(add).get()
        assertEquals(bc1.availableBalanceForSend(), b)
        assertEquals(bc1.availableBalanceForReceive(), a - p - fee)

        val (ac2, commit1) = ac1.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac2.availableBalanceForSend(), a - p - fee)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (bc2, revocation1) = bc1.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc2.availableBalanceForSend(), b)
        assertEquals(bc2.availableBalanceForReceive(), a - p - fee)

        val ac3 = ac2.receiveRevocation(revocation1).get().first
        assertEquals(ac3.availableBalanceForSend(), a - p - fee)
        assertEquals(ac3.availableBalanceForReceive(), b)

        val (bc3, commit2) = bc2.sendCommit(bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc3.availableBalanceForSend(), b)
        assertEquals(bc3.availableBalanceForReceive(), a - p - fee)

        val (ac4, revocation2) = ac3.receiveCommit(commit2, alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac4.availableBalanceForSend(), a - p - fee)
        assertEquals(ac4.availableBalanceForReceive(), b)

        val bc4 = bc3.receiveRevocation(revocation2).get().first
        assertEquals(bc4.availableBalanceForSend(), b)
        assertEquals(bc4.availableBalanceForReceive(), a - p - fee)

        val cmdFail = CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(p, 42)))
        val (bc5, fail) = bc4.sendFail(cmdFail, bob.staticParams.nodeParams.privateKey).get()
        assertEquals(bc5.availableBalanceForSend(), b)
        assertEquals(bc5.availableBalanceForReceive(), a - p - fee)

        val ac5 = ac4.receiveFail(fail).get().first
        assertEquals(ac5.availableBalanceForSend(), a - p - fee)
        assertEquals(ac5.availableBalanceForReceive(), b)

        val (bc6, commit3) = bc5.sendCommit(bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc6.availableBalanceForSend(), b)
        assertEquals(bc6.availableBalanceForReceive(), a - p - fee)

        val (ac6, revocation3) = ac5.receiveCommit(commit3, alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac6.availableBalanceForSend(), a)
        assertEquals(ac6.availableBalanceForReceive(), b)

        val bc7 = bc6.receiveRevocation(revocation3).get().first
        assertEquals(bc7.availableBalanceForSend(), b)
        assertEquals(bc7.availableBalanceForReceive(), a)

        val (ac7, commit4) = ac6.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac7.availableBalanceForSend(), a)
        assertEquals(ac7.availableBalanceForReceive(), b)

        val (bc8, revocation4) = bc7.receiveCommit(commit4, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc8.availableBalanceForSend(), b)
        assertEquals(bc8.availableBalanceForReceive(), a)

        val ac8 = ac7.receiveRevocation(revocation4).get().first
        assertEquals(ac8.availableBalanceForSend(), a)
        assertEquals(ac8.availableBalanceForReceive(), b)
    }

    @Test
    fun `correct values for availableForSend - availableForReceive (multiple htlcs)`() {
        val (alice, bob) = reachNormal()

        val fee = 1720000.msat // fee due to the additional htlc output
        val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase
        val a = (772760000.msat) - fee - funderFeeReserve // initial balance alice
        val b = 190000000.msat // initial balance bob
        val p1 = 10000000.msat // a->b payment
        val p2 = 20000000.msat // a->b payment
        val p3 = 40000000.msat // b->a payment
        val ac0 = alice.commitments
        val bc0 = bob.commitments

        assertTrue(ac0.availableBalanceForSend() > p1 + p2) // alice can afford the payment
        assertTrue(bc0.availableBalanceForSend() > p3) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L

        val (payment_preimage1, cmdAdd1) = TestsHelper.makeCmdAdd(p1, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add1) = ac0.sendAdd(cmdAdd1, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        assertEquals(ac1.availableBalanceForSend(), a - p1 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val (_, cmdAdd2) = TestsHelper.makeCmdAdd(p2, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac2, add2) = ac1.sendAdd(cmdAdd2, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        assertEquals(ac2.availableBalanceForSend(), a - p1 - fee - p2 - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (payment_preimage3, cmdAdd3) = TestsHelper.makeCmdAdd(p3, alice.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (bc1, add3) = bc0.sendAdd(cmdAdd3, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        assertEquals(bc1.availableBalanceForSend(), b - p3) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(bc1.availableBalanceForReceive(), a)

        val bc2 = bc1.receiveAdd(add1).get()
        assertEquals(bc2.availableBalanceForSend(), b - p3)
        assertEquals(bc2.availableBalanceForReceive(), a - p1 - fee)

        val bc3 = bc2.receiveAdd(add2).get()
        assertEquals(bc3.availableBalanceForSend(), b - p3)
        assertEquals(bc3.availableBalanceForReceive(), a - p1 - fee - p2 - fee)

        val ac3 = ac2.receiveAdd(add3).get()
        assertEquals(ac3.availableBalanceForSend(), a - p1 - fee - p2 - fee)
        assertEquals(ac3.availableBalanceForReceive(), b - p3)

        val (ac4, commit1) = ac3.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac4.availableBalanceForSend(), a - p1 - fee - p2 - fee)
        assertEquals(ac4.availableBalanceForReceive(), b - p3)

        val (bc4, revocation1) = bc3.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc4.availableBalanceForSend(), b - p3)
        assertEquals(bc4.availableBalanceForReceive(), a - p1 - fee - p2 - fee)

        val ac5 = ac4.receiveRevocation(revocation1).get().first
        assertEquals(ac5.availableBalanceForSend(), a - p1 - fee - p2 - fee)
        assertEquals(ac5.availableBalanceForReceive(), b - p3)

        val (bc5, commit2) = bc4.sendCommit(bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc5.availableBalanceForSend(), b - p3)
        assertEquals(bc5.availableBalanceForReceive(), a - p1 - fee - p2 - fee)

        val (ac6, revocation2) = ac5.receiveCommit(commit2, alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac6.availableBalanceForSend(), a - p1 - fee - p2 - fee - fee) // alice has acknowledged b's hltc so it needs to pay the fee for it
        assertEquals(ac6.availableBalanceForReceive(), b - p3)

        val bc6 = bc5.receiveRevocation(revocation2).get().first
        assertEquals(bc6.availableBalanceForSend(), b - p3)
        assertEquals(bc6.availableBalanceForReceive(), a - p1 - fee - p2 - fee - fee)

        val (ac7, commit3) = ac6.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac7.availableBalanceForSend(), a - p1 - fee - p2 - fee - fee)
        assertEquals(ac7.availableBalanceForReceive(), b - p3)

        val (bc7, revocation3) = bc6.receiveCommit(commit3, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc7.availableBalanceForSend(), b - p3)
        assertEquals(bc7.availableBalanceForReceive(), a - p1 - fee - p2 - fee - fee)

        val ac8 = ac7.receiveRevocation(revocation3).get().first
        assertEquals(ac8.availableBalanceForSend(), a - p1 - fee - p2 - fee - fee)
        assertEquals(ac8.availableBalanceForReceive(), b - p3)

        val cmdFulfill1 = CMD_FULFILL_HTLC(0, payment_preimage1)
        val (bc8, fulfill1) = bc7.sendFulfill(cmdFulfill1).get()
        assertEquals(bc8.availableBalanceForSend(), b + p1 - p3) // as soon as we have the fulfill, the balance increases
        assertEquals(bc8.availableBalanceForReceive(), a - p1 - fee - p2 - fee - fee)

        val cmdFail2 = CMD_FAIL_HTLC(1, CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(p2, 42)))
        val (bc9, fail2) = bc8.sendFail(cmdFail2, bob.staticParams.nodeParams.privateKey).get()
        assertEquals(bc9.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc9.availableBalanceForReceive(), a - p1 - fee - p2 - fee - fee) // a's balance won't return to previous before she acknowledges the fail

        val cmdFulfill3 = CMD_FULFILL_HTLC(0, payment_preimage3)
        val (ac9, fulfill3) = ac8.sendFulfill(cmdFulfill3).get()
        assertEquals(ac9.availableBalanceForSend(), a - p1 - fee - p2 - fee + p3)
        assertEquals(ac9.availableBalanceForReceive(), b - p3)

        val ac10 = ac9.receiveFulfill(fulfill1).get().first
        assertEquals(ac10.availableBalanceForSend(), a - p1 - fee - p2 - fee + p3)
        assertEquals(ac10.availableBalanceForReceive(), b + p1 - p3)

        val ac11 = ac10.receiveFail(fail2).get().first
        assertEquals(ac11.availableBalanceForSend(), a - p1 - fee - p2 - fee + p3)
        assertEquals(ac11.availableBalanceForReceive(), b + p1 - p3)

        val bc10 = bc9.receiveFulfill(fulfill3).get().first
        assertEquals(bc10.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc10.availableBalanceForReceive(), a - p1 - fee - p2 - fee + p3) // the fee for p3 disappears

        val (ac12, commit4) = ac11.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac12.availableBalanceForSend(), a - p1 - fee - p2 - fee + p3)
        assertEquals(ac12.availableBalanceForReceive(), b + p1 - p3)

        val (bc11, revocation4) = bc10.receiveCommit(commit4, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc11.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc11.availableBalanceForReceive(), a - p1 - fee - p2 - fee + p3)

        val ac13 = ac12.receiveRevocation(revocation4).get().first
        assertEquals(ac13.availableBalanceForSend(), a - p1 - fee - p2 - fee + p3)
        assertEquals(ac13.availableBalanceForReceive(), b + p1 - p3)

        val (bc12, commit5) = bc11.sendCommit(bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc12.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc12.availableBalanceForReceive(), a - p1 - fee - p2 - fee + p3)

        val (ac14, revocation5) = ac13.receiveCommit(commit5, alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac14.availableBalanceForSend(), a - p1 + p3)
        assertEquals(ac14.availableBalanceForReceive(), b + p1 - p3)

        val bc13 = bc12.receiveRevocation(revocation5).get().first
        assertEquals(bc13.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc13.availableBalanceForReceive(), a - p1 + p3)

        val (ac15, commit6) = ac14.sendCommit(alice.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac15.availableBalanceForSend(), a - p1 + p3)
        assertEquals(ac15.availableBalanceForReceive(), b + p1 - p3)

        val (bc14, revocation6) = bc13.receiveCommit(commit6, bob.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc14.availableBalanceForSend(), b + p1 - p3)
        assertEquals(bc14.availableBalanceForReceive(), a - p1 + p3)

        val ac16 = ac15.receiveRevocation(revocation6).get().first
        assertEquals(ac16.availableBalanceForSend(), a - p1 + p3)
        assertEquals(ac16.availableBalanceForReceive(), b + p1 - p3)
    }

    // See https://github.com/lightningnetwork/lightning-rfc/issues/728
    @Test
    fun `funder keeps additional reserve to avoid channel being stuck`() {
        val isFunder = true
        val currentBlockHeight = 144L
        val c = makeCommitments(100000000.msat, 50000000.msat, 2500, 546.sat, isFunder)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(c.availableBalanceForSend(), randomKey().publicKey(), currentBlockHeight)
        val (c1, _) = c.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        assertEquals(c1.availableBalanceForSend(), 0.msat)

        // We should be able to handle a fee increase.
        val (c2, _) = c1.sendFee(CMD_UPDATE_FEE(3000)).get()

        // Now we shouldn't be able to send until we receive enough to handle the updated commit tx fee (even trimmed HTLCs shouldn't be sent).
        val (_, cmdAdd1) = TestsHelper.makeCmdAdd(100.msat, randomKey().publicKey(), currentBlockHeight)
        val e = (c2.sendAdd(cmdAdd1, Origin.Local(UUID.randomUUID()), currentBlockHeight) as Try.Failure<Pair<Commitments, UpdateAddHtlc>>).error
        assertTrue(e is InsufficientFunds)
    }

    @Test
    fun `can send availableForSend`() {
        val currentBlockHeight = 144L
        listOf(true, false).forEach {
            val c = makeCommitments(702000000.msat, 52000000.msat, 2679, 546.sat, it)
            val (_, cmdAdd) = TestsHelper.makeCmdAdd(c.availableBalanceForSend(), randomKey().publicKey(), currentBlockHeight)
            val result = c.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight)
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun `can receive availableForReceive`() {
        val currentBlockHeight = 144L
        listOf(true, false).forEach {
            val c = makeCommitments(31000000.msat, 702000000.msat, 2679, 546.sat, it)
            val add = UpdateAddHtlc(
                randomBytes32(), c.remoteNextHtlcId, c.availableBalanceForReceive(), randomBytes32(), CltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket
            )
            val result = c.receiveAdd(add)
            assertTrue(result.isSuccess)
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    companion object {
        fun makeCommitments(toLocal: MilliSatoshi, toRemote: MilliSatoshi, feeRatePerKw: Long = 0, dustLimit: Satoshi = 0.sat, isFunder: Boolean = true, announceChannel: Boolean = true): Commitments {
            val localParams = LocalParams(
                randomKey().publicKey(), KeyPath("42"), dustLimit, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50, isFunder, ByteVector.empty, null, Features.empty
            )
            val remoteParams = RemoteParams(
                randomKey().publicKey(), dustLimit, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50,
                randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(),
                Features.empty
            )
            val commitmentInput = Helpers.Funding.makeFundingInputInfo(
                randomBytes32(),
                0, (toLocal + toRemote).truncateToSatoshi(), randomKey().publicKey(), remoteParams.fundingPubKey
            )
            return Commitments(
                ChannelVersion.STANDARD,
                localParams,
                remoteParams,
                channelFlags = if (announceChannel) ChannelFlags.AnnounceChannel else ChannelFlags.Empty,
                LocalCommit(
                    0, CommitmentSpec(setOf(), feeRatePerKw, toLocal, toRemote), PublishableTxs(
                        Transactions.TransactionWithInputInfo.CommitTx(commitmentInput, Transaction(2, listOf(), listOf(), 0)), listOf()
                    )
                ),
                RemoteCommit(
                    0, CommitmentSpec(setOf(), feeRatePerKw, toRemote, toLocal), randomBytes32(), randomKey().publicKey()
                ),
                LocalChanges(listOf(), listOf(), listOf()),
                RemoteChanges(listOf(), listOf(), listOf()),
                localNextHtlcId = 1,
                remoteNextHtlcId = 1,
                originChannels = mapOf(),
                remoteNextCommitInfo = Either.Right(randomKey().publicKey()),
                commitInput = commitmentInput,
                remotePerCommitmentSecrets = ShaChain.init,
                channelId = randomBytes32()
            )
        }

        fun makeCommitments(
            toLocal: MilliSatoshi, toRemote: MilliSatoshi, localNodeId: PublicKey, remoteNodeId: PublicKey, announceChannel: Boolean
        ): Commitments {
            val localParams = LocalParams(
                localNodeId, KeyPath("42L"), 0.sat, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50, isFunder = true, ByteVector.empty, null, Features.empty
            )
            val remoteParams = RemoteParams(
                remoteNodeId, 0.sat, Long.MAX_VALUE, 0.sat, 1.msat, CltvExpiryDelta(144), 50, randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey(), Features.empty
            )
            val commitmentInput = Helpers.Funding.makeFundingInputInfo(
                randomBytes32(), 0, (toLocal + toRemote).truncateToSatoshi(), randomKey().publicKey(), remoteParams.fundingPubKey
            )
            return Commitments(
                ChannelVersion.STANDARD,
                localParams,
                remoteParams,
                channelFlags = if (announceChannel) ChannelFlags.AnnounceChannel else ChannelFlags.Empty,
                LocalCommit(
                    0, CommitmentSpec(setOf(), 0, toLocal, toRemote), PublishableTxs(
                        Transactions.TransactionWithInputInfo.CommitTx(
                            commitmentInput, Transaction(2, listOf(), listOf(), 0)
                        ), listOf()
                    )
                ),
                RemoteCommit(
                    0, CommitmentSpec(setOf(), 0, toRemote, toLocal), randomBytes32(), randomKey().publicKey()
                ),
                LocalChanges(listOf(), listOf(), listOf()),
                RemoteChanges(listOf(), listOf(), listOf()),
                localNextHtlcId = 1,
                remoteNextHtlcId = 1,
                originChannels = mapOf(),
                remoteNextCommitInfo = Either.Right(randomKey().publicKey()),
                commitInput = commitmentInput,
                remotePerCommitmentSecrets = ShaChain.init,
                channelId = randomBytes32()
            )
        }
    }
}