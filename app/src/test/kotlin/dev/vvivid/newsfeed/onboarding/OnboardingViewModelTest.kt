package dev.vvivid.newsfeed.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingViewModelTest {
    @Test
    fun `starts with an empty address and no feedback`() {
        val viewModel = OnboardingViewModel()

        assertEquals(OnboardingUiState(serverAddress = "", feedback = AddressFeedback.None), viewModel.uiState.value)
    }

    @Test
    fun `continue without an address reports it as empty`() {
        val viewModel = OnboardingViewModel()

        viewModel.onContinue()

        assertEquals(AddressFeedback.Invalid(ServerAddressProblem.Empty), viewModel.uiState.value.feedback)
    }

    @Test
    fun `continue with an invalid address reports the problem and keeps the input`() {
        val viewModel = OnboardingViewModel()
        viewModel.onServerAddressChange("http://news.example.com")

        viewModel.onContinue()

        assertEquals(
            OnboardingUiState(
                serverAddress = "http://news.example.com",
                feedback = AddressFeedback.Invalid(ServerAddressProblem.HttpNotAllowed),
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `continue with a valid address says sign-in is unavailable for the normalized origin`() {
        val viewModel = OnboardingViewModel()
        viewModel.onServerAddressChange("News.Example.com/feed")

        viewModel.onContinue()

        assertEquals(
            OnboardingUiState(
                serverAddress = "News.Example.com/feed",
                feedback = AddressFeedback.SignInUnavailable("https://news.example.com"),
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `editing the address clears the previous feedback`() {
        val viewModel = OnboardingViewModel()
        viewModel.onServerAddressChange("news.example.com")
        viewModel.onContinue()

        viewModel.onServerAddressChange("news.example.org")

        assertEquals(OnboardingUiState(serverAddress = "news.example.org"), viewModel.uiState.value)
    }
}
