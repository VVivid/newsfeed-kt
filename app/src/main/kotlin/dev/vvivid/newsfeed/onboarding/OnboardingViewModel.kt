package dev.vvivid.newsfeed.onboarding

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the onboarding screen shows. */
data class OnboardingUiState(
    val serverAddress: String = "",
    val feedback: AddressFeedback = AddressFeedback.None,
)

/** The screen's answer to the last Continue. Editing the address clears it. */
sealed interface AddressFeedback {
    data object None : AddressFeedback

    data class Invalid(
        val problem: ServerAddressProblem,
    ) : AddressFeedback

    /** The address is valid, but this version cannot sign in yet. */
    data class SignInUnavailable(
        val origin: String,
    ) : AddressFeedback
}

/**
 * Validates the server address on Continue. This version has no server sign-in: a valid address
 * only produces [AddressFeedback.SignInUnavailable]. Nothing is stored and no server is contacted.
 */
class OnboardingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onServerAddressChange(address: String) {
        // Feedback described the previous address, so it no longer applies.
        _uiState.update { it.copy(serverAddress = address, feedback = AddressFeedback.None) }
    }

    fun onContinue() {
        _uiState.update { state ->
            val feedback =
                when (val result = normalizeServerOrigin(state.serverAddress)) {
                    is ServerOriginResult.Valid -> AddressFeedback.SignInUnavailable(result.origin)
                    is ServerOriginResult.Invalid -> AddressFeedback.Invalid(result.problem)
                }
            state.copy(feedback = feedback)
        }
    }
}
