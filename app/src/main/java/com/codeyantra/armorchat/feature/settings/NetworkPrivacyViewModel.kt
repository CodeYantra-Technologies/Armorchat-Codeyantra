package com.codeyantra.armorchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.core.network.ServicePolicyController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class NetworkPrivacyViewModel @Inject constructor(
    relayRegistry: RelayRegistry,
    servicePolicyController: ServicePolicyController,
) : ViewModel() {

    val authorizedRelays: List<RelayNode> = relayRegistry.allAuthorized()
    val bootstrapNode: RelayNode = relayRegistry.bootstrap()
    val actualHopCount: Int = authorizedRelays.size

    val networkAvailability: StateFlow<NetworkAvailability> = servicePolicyController.availability
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkAvailability())
}
