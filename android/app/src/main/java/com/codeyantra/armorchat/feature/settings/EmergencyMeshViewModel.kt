package com.codeyantra.armorchat.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshRuntime
import com.codeyantra.armorchat.mesh.MeshPermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class EmergencyMeshViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: EmergencyMeshEngine,
) : ViewModel() {
    val runtime: StateFlow<MeshRuntime> = engine.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        engine.state.value,
    )

    fun missingPermissions(): List<String> = MeshPermissions.missing(context)

    fun setArmed(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) engine.arm() else engine.disarm()
        }
    }
}
