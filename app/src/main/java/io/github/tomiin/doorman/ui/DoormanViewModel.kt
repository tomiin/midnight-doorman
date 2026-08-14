package io.github.tomiin.doorman.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tomiin.doorman.data.ContractAddressStore
import io.github.tomiin.doorman.data.DoormanContract
import io.github.tomiin.doorman.data.IssuerKeyStore
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Drives the Doorman card.
//
// The demo runs all three roles from one device, which is what makes it
// legible on a single screen:
//
//   deploy()          — this device becomes the REGISTRY (the constructor
//                       records issuerId(localSecretKey) as the registry).
//   registerIssuer()  — the registry registers its own tag, so this device
//                       is now also a licensed ISSUER.
//   enrol(year)       — the issuer enrols a HOLDER: one opaque hash binding
//                       holder id, birth year and issuer tag. This is the
//                       on-chain call worth filming; the birth year goes in
//                       as an argument to the issuer's own transaction and
//                       the resulting leaf reveals nothing.
//
// proveOfAge is deliberately NOT wired here. It needs a MerkleTreePath
// witness, and Kuira 0.1.0-alpha05 has no ledger-ADT query to build the path
// with (the generated facade lists HistoricMerkleTree under "Phase-2 ADT
// queries"). Documented in the README rather than faked.
@HiltViewModel
class DoormanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkProvider: MidnightSdkProvider,
    private val addressStore: ContractAddressStore,
    private val keyStore: IssuerKeyStore,
) : ViewModel() {

    private val _state = MutableStateFlow<DoormanUiState>(DoormanUiState.NotReady)
    val state: StateFlow<DoormanUiState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _callStage = MutableStateFlow<ContractCallStage?>(null)
    val callStage: StateFlow<ContractCallStage?> = _callStage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val selectedNetwork: StateFlow<MidnightNetwork> get() = sdkProvider.selectedNetwork
    fun selectNetwork(network: MidnightNetwork) = sdkProvider.selectNetwork(network)

    private val secretKey: ByteArray get() = keyStore.secretKey()

    private var enrolmentJob: Job? = null

    init {
        viewModelScope.launch {
            sdkProvider.sdk.combine(sdkProvider.selectedNetwork) { sdk, net -> sdk to net }
                .collect { (sdk, network) -> recomputeState(sdk, network) }
        }
    }

    private fun recomputeState(sdk: MidnightSdk?, network: MidnightNetwork) {
        val persisted = addressStore.get(network)
        val wasIssuer = (_state.value as? DoormanUiState.Deployed)?.isIssuer ?: false
        _state.value = when {
            sdk == null -> DoormanUiState.NotReady
            persisted == null -> DoormanUiState.ReadyToDeploy
            else -> DoormanUiState.Deployed(persisted, enrolments = null, isIssuer = wasIssuer)
        }
        if (sdk != null && persisted != null) startObserving(sdk, persisted) else stopObserving()
    }

    fun deploy() {
        val sdk = sdkProvider.sdk.value ?: return
        val network = sdkProvider.selectedNetwork.value
        runAction {
            val address = DoormanContract.deploy(context, sdk, secretKey) { _callStage.value = it }
            addressStore.put(network, address)
            // Show the address immediately. If the claim below fails, the
            // deploy still happened and the user should be able to see and
            // retry against it rather than lose the address.
            recomputeState(sdk, network)

            // The constructor could not record who deployed this, so claim the
            // registry as a second transaction. Preprod's indexer takes a while
            // to see a freshly deployed contract, so retry rather than assume a
            // fixed wait is long enough.
            claimWithRetry(sdk, address)
            recomputeState(sdk, network)
        }
    }

    // Claim the registry, retrying while the indexer catches up.
    //
    // A freshly deployed contract is not immediately visible to the indexer, so
    // the first call after a deploy fails with "Contract not found at address".
    // That is transient: wait and try again. Anything else is a real error and
    // is rethrown straight away.
    private suspend fun claimWithRetry(sdk: MidnightSdk, address: String) {
        var wait = 8_000L
        repeat(6) { attempt ->
            try {
                DoormanContract.claimRegistry(context, sdk, address, secretKey) {
                    _callStage.value = it
                }
                return
            } catch (t: Throwable) {
                val notIndexedYet = (t.message ?: "").contains("not found", ignoreCase = true)
                if (!notIndexedYet || attempt == 5) throw t
                _error.value = "Waiting for the indexer to see the contract…"
                delay(wait)
                wait += 4_000L
            }
        }
    }

    // Claim the registry by hand. For a contract that deployed but whose claim
    // did not land, so the address does not have to be thrown away.
    fun claimRegistry() {
        val sdk = sdkProvider.sdk.value ?: return
        val address = (state.value as? DoormanUiState.Deployed)?.address ?: return
        runAction { claimWithRetry(sdk, address) }
    }

    // Stop pointing at this registry. The on-chain contract is untouched.
    fun disconnect() {
        val network = sdkProvider.selectedNetwork.value
        addressStore.clear(network)
        recomputeState(sdkProvider.sdk.value, network)
    }

    // The registry licenses this device as an issuer. Only the registry can
    // do this, and the constructor made this device the registry.
    fun registerSelfAsIssuer() {
        val sdk = sdkProvider.sdk.value ?: return
        val address = (state.value as? DoormanUiState.Deployed)?.address ?: return
        runAction {
            val tag = DoormanContract.issuerIdFor(context, sdk, address, secretKey)
            DoormanContract.registerIssuer(context, sdk, address, secretKey, tag) {
                _callStage.value = it
            }
            _state.update {
                if (it is DoormanUiState.Deployed) it.copy(isIssuer = true) else it
            }
        }
    }

    // Enrol a holder with a birth year. THE on-chain call: what lands is one
    // opaque hash, not the year and not the person.
    fun enrol(birthYear: Long) {
        val sdk = sdkProvider.sdk.value ?: return
        val address = (state.value as? DoormanUiState.Deployed)?.address ?: return
        runAction {
            val holder = DoormanContract.holderIdFor(context, sdk, address, secretKey)
            DoormanContract.enrol(context, sdk, address, secretKey, holder, birthYear) {
                _callStage.value = it
            }
            val fresh = DoormanContract.readEnrolmentCount(readHandleFor(sdk, address))
            _state.update {
                if (it is DoormanUiState.Deployed) it.copy(enrolments = fresh) else it
            }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                block()
            } catch (t: Throwable) {
                _error.value = t.message ?: t::class.simpleName ?: "Unknown error"
            } finally {
                _busy.value = false
                _callStage.value = null
            }
        }
    }

    private var readHandle: MidnightContract? = null
    private var readHandleAddress: String? = null
    private var readHandleSdk: MidnightSdk? = null

    private fun readHandleFor(sdk: MidnightSdk, address: String): MidnightContract {
        if (readHandle == null || readHandleAddress != address || readHandleSdk !== sdk) {
            readHandle = DoormanContract.buildReadHandle(context, sdk, address, secretKey)
            readHandleAddress = address
            readHandleSdk = sdk
        }
        return readHandle!!
    }

    private fun startObserving(sdk: MidnightSdk, address: String) {
        enrolmentJob?.cancel()
        enrolmentJob = viewModelScope.launch {
            val handle = readHandleFor(sdk, address)
            DoormanContract.observeEnrolments(handle)
                .catch { /* non-fatal background read */ }
                .collect { fresh ->
                    _state.update { current ->
                        if (current is DoormanUiState.Deployed && current.address == address) {
                            current.copy(enrolments = fresh)
                        } else current
                    }
                }
        }
    }

    private fun stopObserving() {
        enrolmentJob?.cancel()
        enrolmentJob = null
        readHandle = null
        readHandleAddress = null
        readHandleSdk = null
    }
}
