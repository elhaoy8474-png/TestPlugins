package com.ahwaktv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AhwakTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AhwakTV())
    }
}
