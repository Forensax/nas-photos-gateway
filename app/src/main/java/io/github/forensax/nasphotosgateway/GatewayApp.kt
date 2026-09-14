package io.github.forensax.nasphotosgateway

import android.app.Application

class GatewayApp : Application() {
    val gateway by lazy { GatewayRepository(this) }
}
