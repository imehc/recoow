package imehc.recoow

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import imehc.recoow.core.sync.SyncRuntime
import javax.inject.Inject

@HiltAndroidApp
class RecoowApplication : Application() {
    @Inject lateinit var sync: SyncRuntime
    override fun onCreate() { super.onCreate(); sync.start() }
}
