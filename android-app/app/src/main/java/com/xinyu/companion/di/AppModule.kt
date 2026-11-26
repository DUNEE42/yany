package com.xinyu.companion.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.data.diagnostics.AndroidDiagnosticSink
import com.xinyu.companion.data.network.DeepSeekApi
import com.xinyu.companion.data.network.DeepSeekMemoryExtractor
import com.xinyu.companion.data.repository.ChatRepository
import com.xinyu.companion.data.repository.MemoryRepository
import com.xinyu.companion.data.repository.PerceptionRepository
import com.xinyu.companion.data.search.OpenMeteoWeatherProvider
import com.xinyu.companion.data.search.SearxngWebSearchProvider
import com.xinyu.companion.data.perception.AndroidCoarseNetworkLocationProvider
import com.xinyu.companion.data.perception.AndroidForegroundAppProvider
import com.xinyu.companion.data.perception.AndroidPerceptionAccessChecker
import com.xinyu.companion.data.notification.NotificationAllowlistStore
import com.xinyu.companion.data.reminder.AndroidReminderAlarmScheduler
import com.xinyu.companion.data.reminder.EncryptedReminderStore
import com.xinyu.companion.data.security.ApiSettingsStore
import com.xinyu.companion.data.security.KeystoreSecretStore
import com.xinyu.companion.data.security.DatabasePassphraseProvider
import com.xinyu.companion.data.settings.SharedPreferencesChatStyleStore
import com.xinyu.companion.data.settings.SharedPreferencesMemorySettingsStore
import com.xinyu.companion.data.settings.SharedPreferencesPerceptionSettingsStore
import com.xinyu.companion.domain.ContextAssembler
import com.xinyu.companion.domain.SafetyPolicy
import com.xinyu.companion.domain.coroutines.ApplicationScope
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.memory.MemoryContextBuilder
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryCoordinator
import com.xinyu.companion.domain.memory.MemoryExtractor
import com.xinyu.companion.domain.memory.MemorySettingsStore
import com.xinyu.companion.domain.memory.MemoryStore
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.perception.CoarseNetworkLocationProvider
import com.xinyu.companion.domain.perception.ForegroundAppProvider
import com.xinyu.companion.domain.perception.NotificationObservationStore
import com.xinyu.companion.domain.perception.NotificationSourceSettings
import com.xinyu.companion.domain.perception.PerceptionAccessChecker
import com.xinyu.companion.domain.perception.PerceptionContextCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.perception.WeatherProvider
import com.xinyu.companion.domain.perception.WebSearchProvider
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.reminder.ReminderAlarmScheduler
import com.xinyu.companion.domain.reminder.ReminderRuleStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "xinyu-encrypted.db",
    ).openHelperFactory(
        SupportOpenHelperFactory(passphraseProvider.getOrCreatePassphrase()),
    ).addCallback(
        object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                ensureMemoryFts(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                ensureMemoryFts(db)
            }
        },
    ).addMigrations(
        AppDatabase.MIGRATION_1_2,
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
        AppDatabase.MIGRATION_5_6,
    )
        .build()

    @Provides
    @Singleton
    fun providePassphraseProvider(
        @ApplicationContext context: Context,
    ): DatabasePassphraseProvider = KeystoreSecretStore(context)

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofMinutes(2))
        .callTimeout(java.time.Duration.ofMinutes(5))
        .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideDeepSeekApi(
        client: OkHttpClient,
        json: Json,
    ): DeepSeekApi = DeepSeekApi(client, json)

    @Provides
    @Singleton
    fun provideChatProvider(deepSeekApi: DeepSeekApi): ChatProvider = deepSeekApi

    @Provides
    @Singleton
    fun provideReminderStore(
        @ApplicationContext context: Context,
    ): ReminderRuleStore = EncryptedReminderStore(context)

    @Provides
    @Singleton
    fun provideReminderAlarmScheduler(
        @ApplicationContext context: Context,
    ): ReminderAlarmScheduler = AndroidReminderAlarmScheduler(context)

    @Provides
    @Singleton
    fun provideReminderPermissionChecker(
        @ApplicationContext context: Context,
    ): com.xinyu.companion.domain.reminder.ReminderPermissionChecker =
        com.xinyu.companion.data.reminder.AndroidReminderPermissionChecker(context)

    @Provides
    @Singleton
    fun provideToolRegistry(
        memoryStore: MemoryStore,
        perceptionSettingsStore: PerceptionSettingsStore,
        networkLocationProvider: CoarseNetworkLocationProvider,
        weatherProvider: WeatherProvider,
        webSearchProvider: WebSearchProvider,
        reminderCoordinator: com.xinyu.companion.domain.reminder.ReminderCoordinator,
        reminderPermissionChecker: com.xinyu.companion.domain.reminder.ReminderPermissionChecker,
        json: Json,
    ): com.xinyu.companion.domain.tools.ToolRegistry =
        com.xinyu.companion.domain.tools.ToolRegistry(
            listOf(
                com.xinyu.companion.domain.tools.MemorySearchTool(memoryStore, json),
                com.xinyu.companion.domain.tools.WeatherTool(
                    settingsStore = perceptionSettingsStore,
                    networkLocationProvider = networkLocationProvider,
                    weatherProvider = weatherProvider,
                    json = json,
                ),
                com.xinyu.companion.domain.tools.WebSearchTool(
                    settingsStore = perceptionSettingsStore,
                    searchProvider = webSearchProvider,
                    json = json,
                ),
                com.xinyu.companion.domain.tools.LocalReminderTool(
                    coordinator = reminderCoordinator,
                    permissionChecker = reminderPermissionChecker,
                    json = json,
                ),
            ),
        )

    @Provides
    @Singleton
    fun provideMemoryStore(repository: MemoryRepository): MemoryStore = repository

    @Provides
    @Singleton
    fun provideMemoryExtractor(extractor: DeepSeekMemoryExtractor): MemoryExtractor = extractor

    @Provides
    @Singleton
    fun provideMemorySettingsStore(
        store: SharedPreferencesMemorySettingsStore,
    ): MemorySettingsStore = store

    @Provides
    @Singleton
    fun provideMemoryWorkCoordinator(
        coordinator: MemoryCoordinator,
    ): MemoryWorkCoordinator = coordinator

    @Provides
    @Singleton
    fun provideMemoryContextProvider(
        builder: MemoryContextBuilder,
    ): MemoryContextProvider = builder

    @Provides
    @Singleton
    fun provideNotificationObservationStore(
        repository: PerceptionRepository,
    ): NotificationObservationStore = repository

    @Provides
    @Singleton
    fun provideNotificationSourceSettings(
        store: NotificationAllowlistStore,
    ): NotificationSourceSettings = store

    @Provides
    @Singleton
    fun providePerceptionAccessChecker(
        checker: AndroidPerceptionAccessChecker,
    ): PerceptionAccessChecker = checker

    @Provides
    @Singleton
    fun providePerceptionSettingsStore(
        store: SharedPreferencesPerceptionSettingsStore,
    ): PerceptionSettingsStore = store

    @Provides
    @Singleton
    fun provideForegroundAppProvider(
        provider: AndroidForegroundAppProvider,
    ): ForegroundAppProvider = provider

    @Provides
    @Singleton
    fun provideCoarseNetworkLocationProvider(
        provider: AndroidCoarseNetworkLocationProvider,
    ): CoarseNetworkLocationProvider = provider

    @Provides
    @Singleton
    fun provideWebSearchProvider(
        provider: SearxngWebSearchProvider,
    ): WebSearchProvider = provider

    @Provides
    @Singleton
    fun provideWeatherProvider(
        provider: OpenMeteoWeatherProvider,
    ): WeatherProvider = provider

    @Provides
    @Singleton
    fun providePerceptionContextProvider(
        coordinator: PerceptionContextCoordinator,
    ): PerceptionContextProvider = coordinator

    @Provides
    @Singleton
    fun provideConversationStore(repository: ChatRepository): ChatConversationStore = repository

    @Provides
    @Singleton
    fun provideProviderSettingsStore(
        @ApplicationContext context: Context,
    ): ProviderSettingsStore = ApiSettingsStore(context)

    @Provides
    @Singleton
    fun provideChatStyleStore(
        @ApplicationContext context: Context,
    ): ChatStyleStore = SharedPreferencesChatStyleStore(context)

    @Provides
    @Singleton
    fun provideCompanionProfileStore(
        @ApplicationContext context: Context,
    ): com.xinyu.companion.domain.settings.CompanionProfileStore =
        com.xinyu.companion.data.settings.SharedPreferencesCompanionProfileStore(context)

    @Provides
    @Singleton
    fun provideCompanionAvatarStorage(
        @ApplicationContext context: Context,
    ): com.xinyu.companion.data.settings.CompanionAvatarStorage =
        com.xinyu.companion.data.settings.LocalCompanionAvatarStorage(context)

    @Provides
    @Singleton
    fun provideContextAssembler(): ContextAssembler = ContextAssembler()

    @Provides
    @Singleton
    fun provideSafetyPolicy(): SafetyPolicy = SafetyPolicy()

    @Provides
    @Singleton
    fun provideDiagnosticSink(): DiagnosticSink = AndroidDiagnosticSink()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun ensureMemoryFts(database: SupportSQLiteDatabase) {
        runCatching {
            database.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `memory_items_fts` " +
                    "USING fts4(`memoryId`, `statement`, `subject`, `predicate`, `objectText`)",
            )
        }
    }
}

