package com.atomkarnickel.aviationoptimizer.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.atomkarnickel.aviationoptimizer.BuildConfig
import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.AirportDirectory
import com.atomkarnickel.aviationoptimizer.data.BackendSearchRepository
import com.atomkarnickel.aviationoptimizer.data.DataStoreSettingsStore
import com.atomkarnickel.aviationoptimizer.data.FavoritesStore
import com.atomkarnickel.aviationoptimizer.data.GitHubReleaseRepository
import com.atomkarnickel.aviationoptimizer.data.LogbookStore
import com.atomkarnickel.aviationoptimizer.data.ReleaseRepository
import com.atomkarnickel.aviationoptimizer.data.RoomFavoritesStore
import com.atomkarnickel.aviationoptimizer.data.RoomLogbookStore
import com.atomkarnickel.aviationoptimizer.data.SearchRepository
import com.atomkarnickel.aviationoptimizer.data.SettingsStore
import com.atomkarnickel.aviationoptimizer.data.local.AppDatabase
import com.atomkarnickel.aviationoptimizer.data.remote.GitHubApi
import com.atomkarnickel.aviationoptimizer.ui.about.AppInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // flexible Suchen fragen viele Datumskombinationen ab
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "AviationTripOptimizer-Android/${BuildConfig.VERSION_NAME}").build())
        }
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "aviation.db").build()

    @Provides @Singleton
    fun settingsStore(@ApplicationContext context: Context, json: Json): SettingsStore = DataStoreSettingsStore(context.dataStore, json)

    @Provides @Singleton
    fun searchRepository(settings: SettingsStore, client: OkHttpClient, json: Json): SearchRepository =
        BackendSearchRepository(settings, client, json)

    @Provides @Singleton
    fun gitHubApi(client: OkHttpClient, json: Json): GitHubApi = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Accept", "application/vnd.github+json").build())
        }.build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(GitHubApi::class.java)

    @Provides @Singleton
    fun releaseRepository(api: GitHubApi): ReleaseRepository = GitHubReleaseRepository(api)

    @Provides @Singleton
    fun favorites(db: AppDatabase, json: Json): FavoritesStore = RoomFavoritesStore(db.favoriteDao(), json)

    @Provides @Singleton
    fun logbook(db: AppDatabase): LogbookStore = RoomLogbookStore(db.flightLogDao())

    @Provides @Singleton
    fun appInfo(): AppInfo = AppInfo("Aviation Trip Optimizer", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GITHUB_REPOSITORY)

    @Provides @Singleton
    fun catalog(@ApplicationContext context: Context, json: Json): AircraftCatalog = AircraftCatalog.load(context, json)

    @Provides @Singleton
    fun airports(@ApplicationContext context: Context, json: Json): AirportDirectory = AirportDirectory.load(context, json)
}
