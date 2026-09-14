package io.github.forensax.nasphotosgateway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = (application as GatewayApp).gateway
    val state = gateway.state
    fun loadConfig() = gateway.loadConfig()
    fun save(config: GatewayConfig) { viewModelScope.launch { gateway.save(config) } }
    fun execute(action: String) {
        if (action == "unmount") WorkManager.getInstance(getApplication()).cancelUniqueWork("boot-restore")
        viewModelScope.launch { gateway.execute(action) }
    }
}
