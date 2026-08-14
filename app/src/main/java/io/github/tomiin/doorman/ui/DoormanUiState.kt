package io.github.tomiin.doorman.ui

// Doorman card state.
//
//   NotReady      — SDK not bootstrapped yet (sigil not forged, or wallet
//                   runtime not ready). The panels above handle that; this
//                   card flips automatically.
//
//   ReadyToDeploy — SDK up, nothing deployed on this network yet. A deploy
//                   still needs NIGHT + DUST; tapping without funds surfaces
//                   a clear error rather than gating the button.
//
//   Deployed      — a Doorman registry is live on this network. Shows how
//                   many people have been enrolled and whether this device
//                   has registered itself as an issuer.
sealed interface DoormanUiState {
    data object NotReady : DoormanUiState
    data object ReadyToDeploy : DoormanUiState
    data class Deployed(
        val address: String,
        val enrolments: Long?,
        val isIssuer: Boolean = false,
    ) : DoormanUiState
}
