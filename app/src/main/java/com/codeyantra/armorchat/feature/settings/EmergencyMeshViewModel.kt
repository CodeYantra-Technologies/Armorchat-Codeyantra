package com.codeyantra.armorchat.feature.settings

import androidx.lifecycle.ViewModel
import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus
import com.codeyantra.armorchat.domain.model.MeshHop
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class EmergencyMeshViewModel @Inject constructor() : ViewModel() {

    val meshStatus: EmergencyMeshStatus = EmergencyMeshStatus.UNAVAILABLE
    val isAutoModeEnabled: Boolean = false

    // Architecture-ready future mesh route pipeline
    val meshHops: List<MeshHop> = listOf(
        MeshHop(nodeId = "self", label = "Your Device", role = "Local MLS E2EE"),
    )
}
