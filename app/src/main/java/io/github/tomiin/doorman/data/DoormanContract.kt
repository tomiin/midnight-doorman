package io.github.tomiin.doorman.data

import android.content.Context
import com.midnight.kuira.contract.generated.DoormanContract as Generated
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigInteger

// Thin wrapper around MidnightContract for Doorman. Loads assets, holds the
// wiring constants, and gives the ViewModel a tight surface.
//
// The io.github.kuiralabs.contract plugin syncs the compiled contract into the
// app's assets: runtime JS at runtime/doorman-contract.js, circuit keys at
// keys/<circuit>.{prover,verifier,bzkir}.
internal object DoormanContract {

    private const val NAME = Generated.CONTRACT_ALIAS
    private const val CONTRACT_JS_ASSET = Generated.RUNTIME_ASSET
    private const val KEYS_DIR = Generated.KEYS_ASSET_DIR

    // Every circuit that lands on-chain. Deploy embeds each one's verifier key
    // in the contract artifact, so all of them are loaded up front.
    private val CIRCUITS = listOf(
        "claimRegistry",
        "registerIssuer",
        "revokeIssuer",
        "advanceYear",
        "enrol",
        // "proveOfAge",  // removed in the Merkle-tree probe build
        "hasBeenAdmitted",
        "isRegisteredIssuer",
        "thisYear",
    )

    private fun loadVerifierKeys(context: Context): Map<String, ByteArray> =
        CIRCUITS.associateWith { circuit ->
            context.assets.open("$KEYS_DIR/$circuit.verifier").use { it.readBytes() }
        }

    private fun installProvingKeys(context: Context) {
        ProvingKeyManager(context).installCircuitKeysFromAssets(KEYS_DIR)
    }

    private fun buildHandle(
        context: Context,
        sdk: MidnightSdk,
        address: String?,
        forWrite: Boolean,
        secretKey: ByteArray,
    ): MidnightContract = MidnightContract.create(sdk.config) {
        name = NAME
        contractJs = context.assets.open(CONTRACT_JS_ASSET)
        if (address != null) this.address = address
        if (forWrite) {
            coinPublicKey = sdk.coinPublicKey
            circuitVerifierKeys = loadVerifierKeys(context)
        }
        // EVERY declared witness must be present, even ones the circuit being
        // called never invokes. The Compact runtime validates the whole set up
        // front: omit one and the constructor dies with "first (witnesses)
        // argument to Contract constructor does not contain a function-valued
        // field named <x>".
        //
        // Only localSecretKey carries a real value. It is a WITNESS, not an
        // argument: consumed inside the proof, never on-chain.
        witness("localSecretKey") { WitnessResult(null, secretKey) }

        // The remaining three exist only to satisfy that check. They are read
        // solely by proveOfAge, which is not wired up (see the class comment),
        // so these lambdas never actually run.
        //
        // Note WitnessResult takes a ByteArray and nothing else, whatever the
        // Compact type is. Every witness value is hand-packed bytes, including
        // a Uint<64> and, awkwardly, a whole MerkleTreePath struct.
        witness("birthYear") { WitnessResult(null, ByteArray(32)) }
        witness("enrollingIssuer") { WitnessResult(null, ByteArray(32)) }
        witness("enrolmentPath") { WitnessResult(null, ByteArray(32)) }
    }

    suspend fun deploy(
        context: Context,
        sdk: MidnightSdk,
        secretKey: ByteArray,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ): String {
        installProvingKeys(context)
        val handle = buildHandle(context, sdk, address = null, forWrite = true, secretKey = secretKey)
        return handle.deploy(onProgress = onProgress).contractAddress
    }

    // ---- Registry ----------------------------------------------------------

    // Claim the registry. Must run straight after deploy: the constructor
    // cannot read witnesses under Kuira, so the deployer's identity is
    // recorded here instead.
    suspend fun claimRegistry(
        context: Context,
        sdk: MidnightSdk,
        address: String,
        secretKey: ByteArray,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ) {
        installProvingKeys(context)
        Generated(buildHandle(context, sdk, address, forWrite = true, secretKey = secretKey))
            .claimRegistry(onProgress = onProgress)
    }

    suspend fun registerIssuer(
        context: Context,
        sdk: MidnightSdk,
        address: String,
        secretKey: ByteArray,
        issuerTag: ByteArray,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ) {
        installProvingKeys(context)
        Generated(buildHandle(context, sdk, address, forWrite = true, secretKey = secretKey))
            .registerIssuer(issuerTag, onProgress = onProgress)
    }

    suspend fun advanceYear(
        context: Context,
        sdk: MidnightSdk,
        address: String,
        secretKey: ByteArray,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ) {
        installProvingKeys(context)
        Generated(buildHandle(context, sdk, address, forWrite = true, secretKey = secretKey))
            .advanceYear(onProgress = onProgress)
    }

    // ---- Issuer ------------------------------------------------------------

    // The on-chain call an issuer makes. Writes one opaque hash binding holder,
    // birth year and issuer tag. No personal data leaves the device.
    suspend fun enrol(
        context: Context,
        sdk: MidnightSdk,
        address: String,
        secretKey: ByteArray,
        holderId: ByteArray,
        birthYear: Long,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ) {
        installProvingKeys(context)
        Generated(buildHandle(context, sdk, address, forWrite = true, secretKey = secretKey))
            .enrol(holderId, BigInteger.valueOf(birthYear), onProgress = onProgress)
    }

    // ---- The door (disabled in the Merkle-tree probe build) ----
//    // ---- The door ----------------------------------------------------------

//    // Proves membership plus the age predicate on-device and records a
//    // venue-specific pseudonym.
//    suspend fun proveOfAge(
//        context: Context,
//        sdk: MidnightSdk,
//        address: String,
//        secretKey: ByteArray,
//        venueId: ByteArray,
//        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
//    ) {
//        installProvingKeys(context)
//        Generated(buildHandle(context, sdk, address, forWrite = true, secretKey = secretKey))
//            .proveOfAge(venueId, onProgress = onProgress)
//    }

    // ---- Local derivations (pure circuits, no transaction) ------------------

    suspend fun holderIdFor(
        context: Context,
        sdk: MidnightSdk,
        address: String,
        secretKey: ByteArray,
    ): ByteArray =
        Generated(buildHandle(context, sdk, address, forWrite = false, secretKey = secretKey)).localHolderId(secretKey)

    suspend fun issuerIdFor(
        context: Context,
        sdk: MidnightSdk,
        address: String,
        secretKey: ByteArray,
    ): ByteArray =
        Generated(buildHandle(context, sdk, address, forWrite = false, secretKey = secretKey)).localIssuerId(secretKey)

    // ---- Reads -------------------------------------------------------------

    fun buildReadHandle(
        context: Context,
        sdk: MidnightSdk,
        address: String,
        secretKey: ByteArray,
    ): MidnightContract =
        buildHandle(context, sdk, address = address, forWrite = false, secretKey = secretKey)

    suspend fun readEnrolmentCount(handle: MidnightContract): Long =
        Generated(handle).ledger().enrolmentCount.toLong()

    suspend fun readAdmissionCount(handle: MidnightContract): Long =
        Generated(handle).ledger().admissionCount.toLong()

    suspend fun readCurrentYear(handle: MidnightContract): Long =
        Generated(handle).ledger().currentYear.toLong()

    fun observeEnrolments(handle: MidnightContract): Flow<Long> =
        Generated(handle).observeLedger().map { it.enrolmentCount.toLong() }

    fun observeAdmissions(handle: MidnightContract): Flow<Long> =
        Generated(handle).observeLedger().map { it.admissionCount.toLong() }
}
