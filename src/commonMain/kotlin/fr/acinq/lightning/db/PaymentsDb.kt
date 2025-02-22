package fr.acinq.lightning.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.FailureMessage
import kotlinx.serialization.Serializable

interface PaymentsDb : IncomingPaymentsDb, OutgoingPaymentsDb {
    /** List sent and received payments (with most recent payments first). */
    suspend fun listPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<WalletPayment>
}

interface IncomingPaymentsDb {
    /** Add a new expected incoming payment (not yet received). */
    suspend fun addIncomingPayment(preimage: ByteVector32, origin: IncomingPayment.Origin, createdAt: Long = currentTimestampMillis())

    /** Get information about an incoming payment (paid or not) for the given payment hash, if any. */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment?

    /**
     * Mark an incoming payment as received (paid).
     * Note that this function assumes that there is a matching payment request in the DB, otherwise it will be a no-op.
     *
     * This method is additive:
     * - receivedWith set is appended to the existing set in database.
     * - receivedAt must be updated in database.
     *
     * @param receivedWith is a set containing the payment parts holding the incoming amount, its aggregated amount should be equal to the amount param.
     */
    suspend fun receivePayment(paymentHash: ByteVector32, receivedWith: Set<IncomingPayment.ReceivedWith>, receivedAt: Long = currentTimestampMillis())

    /** Simultaneously add and receive a payment. Use this method when receiving a spontaneous payment, for example a swap-in payment. */
    suspend fun addAndReceivePayment(preimage: ByteVector32, origin: IncomingPayment.Origin, receivedWith: Set<IncomingPayment.ReceivedWith>, createdAt: Long = currentTimestampMillis(), receivedAt: Long = currentTimestampMillis())

    /** Update the channel id of the payments parts that have been received with a new channel, for a given payment hash. If there is no payments for this payment hash,
     * or if the payment has not received any payment parts yet, then this method is a no-op. */
    suspend fun updateNewChannelReceivedWithChannelId(paymentHash: ByteVector32, channelId: ByteVector32)

    /** List received payments (with most recent payments first). */
    suspend fun listReceivedPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<IncomingPayment>

    /** List incoming payments (with the most recent payments first). */
    suspend fun listIncomingPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<IncomingPayment>

    /** List expired unpaid normal payments created within specified time range (with the most recent payments first). */
    suspend fun listExpiredPayments(fromCreatedAt: Long = 0, toCreatedAt: Long = currentTimestampMillis()): List<IncomingPayment>

    /** Remove a pending incoming payment.*/
    suspend fun removeIncomingPayment(paymentHash: ByteVector32): Boolean
}

interface OutgoingPaymentsDb {
    /** Add a new pending outgoing payment (not yet settled). */
    suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment)

    /** Get information about an outgoing payment (settled or not). */
    suspend fun getOutgoingPayment(id: UUID): OutgoingPayment?

    /** Mark an outgoing payment as completed (failed, succeeded, mined). */
    suspend fun completeOutgoingPayment(id: UUID, completed: OutgoingPayment.Status.Completed)

    suspend fun completeOutgoingPayment(id: UUID, finalFailure: FinalFailure, completedAt: Long = currentTimestampMillis()) =
        completeOutgoingPayment(id, OutgoingPayment.Status.Completed.Failed(finalFailure, completedAt))

    suspend fun completeOutgoingPayment(id: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis()) =
        completeOutgoingPayment(id, OutgoingPayment.Status.Completed.Succeeded.OffChain(preimage, completedAt))

    /** Add new partial payments to a pending outgoing payment. */
    suspend fun addOutgoingParts(parentId: UUID, parts: List<OutgoingPayment.Part>)

    /** Mark an outgoing payment part as failed. */
    suspend fun updateOutgoingPart(partId: UUID, failure: Either<ChannelException, FailureMessage>, completedAt: Long = currentTimestampMillis())

    /** Mark an outgoing payment part as succeeded. This should not update the parent payment, since some parts may still be pending. */
    suspend fun updateOutgoingPart(partId: UUID, preimage: ByteVector32, completedAt: Long = currentTimestampMillis())

    /** Get information about an outgoing payment from the id of one of its parts. */
    suspend fun getOutgoingPart(partId: UUID): OutgoingPayment?

    /** List all the outgoing payment attempts that tried to pay the given payment hash. */
    suspend fun listOutgoingPayments(paymentHash: ByteVector32): List<OutgoingPayment>

    /** List outgoing payments (with most recent payments first). */
    suspend fun listOutgoingPayments(count: Int, skip: Int, filters: Set<PaymentTypeFilter> = setOf()): List<OutgoingPayment>
}

enum class PaymentTypeFilter { Normal, KeySend, SwapIn, SwapOut, ChannelClosing }

/** A payment made to or from the wallet. */
sealed class WalletPayment {
    /** Absolute time in milliseconds since UNIX epoch when the payment was completed. */
    fun completedAt(): Long = when (this) {
        is IncomingPayment -> received?.receivedAt ?: 0
        is OutgoingPayment -> when (status) {
            is OutgoingPayment.Status.Completed -> status.completedAt
            else -> 0
        }
    }

    /** Fees applied to complete this payment. */
    abstract val fees: MilliSatoshi

    /**
     * The actual amount that has been sent or received:
     * - for outgoing payments, the fee is included. This is what left the wallet;
     * - for incoming payments, the is the amount AFTER the fees are applied. This is what went into the wallet.
     */
    abstract val amount: MilliSatoshi
}

/**
 * An incoming payment received by this node.
 * At first it is in a pending state, then will become either a success (if we receive a matching payment) or a failure (if the payment request expires).
 *
 * @param preimage payment preimage, which acts as a proof-of-payment for the payer.
 * @param origin origin of a payment (normal, swap, etc).
 * @param received funds received for this payment, null if no funds have been received yet.
 * @param createdAt absolute time in milliseconds since UNIX epoch when the payment request was generated.
 */
data class IncomingPayment(val preimage: ByteVector32, val origin: Origin, val received: Received?, val createdAt: Long = currentTimestampMillis()) : WalletPayment() {
    constructor(preimage: ByteVector32, origin: Origin) : this(preimage, origin, null, currentTimestampMillis())

    val paymentHash: ByteVector32 = Crypto.sha256(preimage).toByteVector32()

    /** Total fees paid to receive this payment. */
    override val fees: MilliSatoshi = received?.fees ?: 0.msat

    /** Total amount actually received for this payment after applying the fees. If someone sent you 500 and the fee was 10, this amount will be 490. */
    override val amount: MilliSatoshi = received?.amount ?: 0.msat

    sealed class Origin {
        /** A normal, invoice-based lightning payment. */
        data class Invoice(val paymentRequest: PaymentRequest) : Origin()

        /** KeySend payments are spontaneous donations for which we didn't create an invoice. */
        object KeySend : Origin()

        /** Swap-in works by sending an on-chain transaction to a swap server, which will pay us in exchange. We may not know the origin address. */
        data class SwapIn(val address: String?) : Origin()

        fun matchesFilters(filters: Set<PaymentTypeFilter>): Boolean = when (this) {
            is Invoice -> filters.isEmpty() || filters.contains(PaymentTypeFilter.Normal)
            is KeySend -> filters.isEmpty() || filters.contains(PaymentTypeFilter.KeySend)
            is SwapIn -> filters.isEmpty() || filters.contains(PaymentTypeFilter.SwapIn)
        }
    }

    data class Received(val receivedWith: Set<ReceivedWith>, val receivedAt: Long = currentTimestampMillis()) {
        /** Total amount received after applying the fees. */
        val amount: MilliSatoshi = receivedWith.map { it.amount }.sum()
        /** Fees applied to receive this payment. */
        val fees: MilliSatoshi = receivedWith.map { it.fees }.sum()
    }

    sealed class ReceivedWith {
        /** Amount received for this part after applying the fees. This is the final amount we can use. */
        abstract val amount: MilliSatoshi
        /** Fees applied to receive this part. Is zero for Lightning payments. */
        abstract val fees: MilliSatoshi

        /** Payment was received via existing lightning channels. */
        data class LightningPayment(override val amount: MilliSatoshi, val channelId: ByteVector32, val htlcId: Long) : ReceivedWith() {
            override val fees: MilliSatoshi = 0.msat // with Lightning, the fee is paid by the sender
        }

        /**
         * Payment was received via a new channel opened to us.
         *
         * @param amount Our side of the balance of this channel when it's created. This is the amount pushed to us once the creation fees are applied.
         * @param fees Fees paid to open this channel.
         * @param channelId the long id of the channel created to receive this payment. May be null if the channel id is not known.
         */
        data class NewChannel(override val amount: MilliSatoshi, override val fees: MilliSatoshi, val channelId: ByteVector32?) : ReceivedWith()
    }

    /** A payment expires if its origin is [Origin.Invoice] and its invoice has expired. [Origin.KeySend] or [Origin.SwapIn] do not expire. */
    fun isExpired(): Boolean = origin is Origin.Invoice && origin.paymentRequest.isExpired()
}

/**
 * An outgoing payment sent by this node.
 * The payment may be split in multiple parts, which may fail, be retried, and then either succeed or fail.
 *
 * @param id internal payment identifier.
 * @param recipientAmount total amount that will be received by the final recipient (NB: it doesn't contain the fees paid).
 * @param recipient final recipient nodeId.
 * @param details details that depend on the payment type (normal payments, swaps, etc).
 * @param parts partial child payments that have actually been sent.
 * @param status current status of the payment.
 */
data class OutgoingPayment(val id: UUID, val recipientAmount: MilliSatoshi, val recipient: PublicKey, val details: Details, val parts: List<Part>, val status: Status, val createdAt: Long = currentTimestampMillis()) : WalletPayment() {
    constructor(id: UUID, amount: MilliSatoshi, recipient: PublicKey, details: Details) : this(id, amount, recipient, details, listOf(), Status.Pending)

    val paymentHash: ByteVector32 = details.paymentHash

    override val fees: MilliSatoshi = when (status) {
        is Status.Pending -> 0.msat
        is Status.Completed.Failed -> 0.msat
        is Status.Completed.Succeeded.OffChain -> {
            parts.filter { it.status is Part.Status.Succeeded }.map { it.amount }.sum() - recipientAmount
        }
        is Status.Completed.Succeeded.OnChain -> {
            recipientAmount - status.claimed.toMilliSatoshi()
        }
    }

    /** Amount actually sent for this payment. It does include the fees. */
    override val amount: MilliSatoshi = recipientAmount + fees

    sealed class Details {
        abstract val paymentHash: ByteVector32

        /** A normal lightning payment. */
        data class Normal(val paymentRequest: PaymentRequest) : Details() {
            override val paymentHash: ByteVector32 = paymentRequest.paymentHash
        }

        /** KeySend payments are spontaneous donations that don't need an invoice from the recipient. */
        data class KeySend(val preimage: ByteVector32) : Details() {
            override val paymentHash: ByteVector32 = Crypto.sha256(preimage).toByteVector32()
        }

        /** Swaps out send a lightning payment to a swap server, which will send an on-chain transaction to a given address. */
        data class SwapOut(val address: String, override val paymentHash: ByteVector32) : Details()

        /** Corresponds to the on-chain payments made when closing a channel. */
        data class ChannelClosing(
            val channelId: ByteVector32,
            val closingAddress: String, // btc address
            // The closingAddress may have been supplied by the user during a mutual close.
            // But in all other cases, the funds are sent to the default Phoenix address derived from the wallet seed.
            // So `isSentToDefaultAddress` means this default Phoenix address was used,
            // and is used by the UI to explain the situation to the user.
            val isSentToDefaultAddress: Boolean
        ) : Details() {
            override val paymentHash: ByteVector32 = channelId.sha256()
        }

        fun matchesFilters(filters: Set<PaymentTypeFilter>): Boolean = when (this) {
            is Normal -> filters.isEmpty() || filters.contains(PaymentTypeFilter.Normal)
            is KeySend -> filters.isEmpty() || filters.contains(PaymentTypeFilter.KeySend)
            is SwapOut -> filters.isEmpty() || filters.contains(PaymentTypeFilter.SwapOut)
            is ChannelClosing -> filters.isEmpty() || filters.contains(PaymentTypeFilter.ChannelClosing)
        }
    }

    sealed class Status {
        object Pending : Status()
        sealed class Completed : Status() {
            abstract val completedAt: Long
            data class Failed(val reason: FinalFailure, override val completedAt: Long = currentTimestampMillis()) : Completed()
            sealed class Succeeded : Completed() {
                data class OffChain(
                    val preimage: ByteVector32,
                    override val completedAt: Long = currentTimestampMillis()
                ) : Succeeded()
                data class OnChain(
                    val txids: List<ByteVector32>,
                    // The `claimed` field represents the sum total of bitcoin tx outputs claimed for the user.
                    // A simplified fees can be calculated as: OutgoingPayment.recipientAmount - claimed
                    // In the future, we plan on storing the closing btc transactions as parts.
                    // Then we can use those parts to calculate the fees, and provide more details to the user.
                    val claimed: Satoshi,
                    val closingType: ChannelClosingType,
                    override val completedAt: Long = currentTimestampMillis()
                ) : Succeeded()
            }
        }
    }

    /**
     * An child payment sent by this node (partial payment of the total amount).
     *
     * @param id internal payment identifier.
     * @param amount amount sent, including fees.
     * @param route payment route used.
     * @param status current status of the payment.
     * @param createdAt absolute time in milliseconds since UNIX epoch when the payment was created.
     */
    data class Part(val id: UUID, val amount: MilliSatoshi, val route: List<HopDesc>, val status: Status, val createdAt: Long = currentTimestampMillis()) {
        sealed class Status {
            object Pending : Status()
            data class Succeeded(val preimage: ByteVector32, val completedAt: Long = currentTimestampMillis()) : Status()

            /**
             * @param remoteFailureCode Bolt4 failure code when the failure came from a remote node (see [FailureMessage]).
             * If null this was a local error (channel unavailable for low-level technical reasons).
             */
            data class Failed(val remoteFailureCode: Int?, val details: String, val completedAt: Long = currentTimestampMillis()) : Status() {
                fun isLocalFailure(): Boolean = remoteFailureCode == null
            }
        }
    }
}

enum class ChannelClosingType {
    Mutual, Local, Remote, Revoked, Other;
}

data class HopDesc(val nodeId: PublicKey, val nextNodeId: PublicKey, val shortChannelId: ShortChannelId? = null) {
    override fun toString(): String = when (shortChannelId) {
        null -> "$nodeId->$nextNodeId"
        else -> "$nodeId->$shortChannelId->$nextNodeId"
    }
}