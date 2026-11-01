package com.ake.ui.feature.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.contract.ModelAssetState
import com.ake.core.ports.ModelAssetStorePort
import com.ake.core.ports.ModelLifecyclePort
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class ModelGateUi(
    val expectedBytes: Long = ModelGateViewModel.VERIFIED_MODEL_BYTES,
    val ready: Boolean = false,
    val initialCheckComplete: Boolean = false,
    val showMeteredWarning: Boolean = false,
)

@HiltViewModel
public class ModelGateViewModel @Inject constructor(
    @ApplicationContext context: Context,
    lifecycle: ModelLifecyclePort,
    assetStore: ModelAssetStorePort,
) : ViewModel() {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _ui = MutableStateFlow(ModelGateUi())
    public val ui: StateFlow<ModelGateUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val ready = assetStore.currentReady()
            _ui.update {
                it.copy(
                    expectedBytes = ready?.expectedBytes?.takeIf { bytes -> bytes > 0L } ?: it.expectedBytes,
                    ready = ready != null || it.ready,
                    initialCheckComplete = true,
                )
            }
        }
        viewModelScope.launch {
            lifecycle.assetState().collect { asset ->
                if (asset.expectedBytes > 0L || asset.state == ModelAssetState.READY) {
                    _ui.update {
                        it.copy(
                            expectedBytes = asset.expectedBytes.takeIf { bytes -> bytes > 0L } ?: it.expectedBytes,
                            ready = asset.state == ModelAssetState.READY,
                        )
                    }
                }
            }
        }
    }

    /** @return true 表示 Wi-Fi 已就绪，可以直接进入下载页。 */
    public fun requestDownload(): Boolean {
        val onWifi = runCatching {
            val network = connectivity.activeNetwork ?: return@runCatching false
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        _ui.update { it.copy(showMeteredWarning = !onWifi) }
        return onWifi
    }

    public fun confirmMeteredDownload() {
        _ui.update { it.copy(showMeteredWarning = false) }
    }

    public fun dismissMeteredWarning() {
        _ui.update { it.copy(showMeteredWarning = false) }
    }

    public companion object {
        /** `ModelLifecycle.GEMMA4_E2B_IT.expectedBytes` 的已核实生产内置值。 */
        public const val VERIFIED_MODEL_BYTES: Long = 2_770_000_000L
    }
}
