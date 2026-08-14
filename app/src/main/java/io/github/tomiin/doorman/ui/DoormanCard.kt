package io.github.tomiin.doorman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.midnight.kuira.dapp.ContractCallProgressBar

// DoormanCard — the on-chain half of the age-proof demo.
//
// Runs all three roles from one device so the whole flow fits on a screen:
// deploy makes this device the registry, register licenses it as an issuer,
// enrol writes one opaque hash for a holder.
@Composable
fun DoormanCard(
    modifier: Modifier = Modifier,
    viewModel: DoormanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val callStage by viewModel.callStage.collectAsState()
    val error by viewModel.error.collectAsState()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Doorman registry",
                style = MaterialTheme.typography.titleMedium,
            )

            when (val s = state) {
                DoormanUiState.NotReady -> NotReadyBody()
                DoormanUiState.ReadyToDeploy -> ReadyToDeployBody(busy = busy, onDeploy = viewModel::deploy)
                is DoormanUiState.Deployed -> DeployedBody(
                    state = s,
                    busy = busy,
                    onClaimRegistry = viewModel::claimRegistry,
                    onRegisterIssuer = viewModel::registerSelfAsIssuer,
                    onEnrol = { viewModel.enrol(2000L) },
                    onDeployNew = viewModel::deploy,
                    onDisconnect = viewModel::disconnect,
                )
            }

            if (busy) {
                if (callStage == null) {
                    CircularProgressIndicator()
                } else {
                    ContractCallProgressBar(
                        stage = callStage,
                        accent = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (error != null) {
                Text(
                    text = "Last error: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NotReadyBody() {
    Text(
        text = "Forge a sigil above, then fund the wallet and register dust.\n\n" +
            "On preprod: fund the wallet address from the panel above at\n" +
            "    faucet.preprod.midnight.network\n" +
            "then tap Register next to DUST. The CLI cannot register dust for\n" +
            "the embedded wallet.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ReadyToDeployBody(busy: Boolean, onDeploy: () -> Unit) {
    Text(
        text = "Deploy a Doorman registry on the current network. This device " +
            "becomes the registry: the constructor records issuerId(your secret " +
            "key) as the authority. The address persists per network.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onDeploy, enabled = !busy) {
        Text(text = "Deploy registry")
    }
}

@Composable
private fun DeployedBody(
    state: DoormanUiState.Deployed,
    busy: Boolean,
    onClaimRegistry: () -> Unit,
    onRegisterIssuer: () -> Unit,
    onEnrol: () -> Unit,
    onDeployNew: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Text(
        text = "Deployed at:\n${state.address}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = state.enrolments?.toString() ?: "—",
        style = MaterialTheme.typography.displayLarge,
    )
    Text(
        text = "people enrolled",
        style = MaterialTheme.typography.bodySmall,
    )

    if (!state.isIssuer) {
        Text(
            text = "Step 1. The registry licenses an issuer. Here it licenses " +
                "itself, so this device can enrol people.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onRegisterIssuer, enabled = !busy) {
            Text(text = "Register me as an issuer")
        }
    } else {
        Text(
            text = "Step 2. Enrol someone born in 2000. What lands on-chain is " +
                "a single opaque hash binding holder, birth year and issuer. " +
                "The year itself is never published.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onEnrol, enabled = !busy) {
            Text(text = "Enrol a holder (born 2000)")
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onClaimRegistry, enabled = !busy) {
            Text(text = "Claim registry")
        }
        TextButton(onClick = onDeployNew, enabled = !busy) {
            Text(text = "Deploy new")
        }
        TextButton(onClick = onDisconnect, enabled = !busy) {
            Text(text = "Disconnect")
        }
    }
    Text(
        text = "Enrolment count updates live from chain.",
        style = MaterialTheme.typography.bodySmall,
    )
}
