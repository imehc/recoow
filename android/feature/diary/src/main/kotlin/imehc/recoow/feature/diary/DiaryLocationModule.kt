package imehc.recoow.feature.diary

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import imehc.recoow.core.domain.location.LocationSource
import imehc.recoow.core.location.AndroidLocationSource

@Module
@InstallIn(SingletonComponent::class)
object DiaryLocationModule {
    @Provides fun location(@ApplicationContext context: Context): LocationSource = AndroidLocationSource(context)
}
