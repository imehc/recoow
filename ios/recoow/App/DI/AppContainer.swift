import Foundation
import Observation

/// 全局依赖容器。所有跨模块服务集中在这里注册，Feature 只依赖协议或仓库。
@MainActor
@Observable
final class AppContainer {
    @ObservationIgnored let database: AppDatabase
    @ObservationIgnored let syncEngine: any SyncEngine
    @ObservationIgnored let deviceIdentifier: DeviceIdentifier
    @ObservationIgnored let locationService: LocationService
    @ObservationIgnored let trackRepository: TrackRepository
    @ObservationIgnored let decisionRepository: DecisionRepository
    @ObservationIgnored let itemLocatorRepository: ItemLocatorRepository
    @ObservationIgnored let reminderRepository: ReminderRepository
    @ObservationIgnored let billRepository: BillRepository
    @ObservationIgnored let foodJournalRepository: FoodJournalRepository
    @ObservationIgnored let mediaAssetRepository: MediaAssetRepository
    @ObservationIgnored let mediaAttachmentRepository: MediaAttachmentRepository
    @ObservationIgnored let diaryRepository: DiaryRepository
    @ObservationIgnored let anniversaryRepository: AnniversaryRepository
    @ObservationIgnored let historyRepository: HistoryRepository
    @ObservationIgnored let dataTransferService: AppDataTransferService
    @ObservationIgnored let notificationScheduler: any AppNotificationScheduling
    @ObservationIgnored let reminderNotificationService: ReminderNotificationService
    @ObservationIgnored let anniversaryNotificationService: AnniversaryNotificationService
    @ObservationIgnored let locationTrackerViewModel: LocationTrackerViewModel
    @ObservationIgnored let featureVisibilitySettings: FeatureVisibilitySettings
    let appPreferences: AppPreferences
    var historyFilterRequest: HistoryFilter?

    init(
        database: AppDatabase,
        syncEngine: any SyncEngine,
        deviceIdentifier: DeviceIdentifier,
        locationService: LocationService,
        trackRepository: TrackRepository,
        decisionRepository: DecisionRepository,
        itemLocatorRepository: ItemLocatorRepository,
        reminderRepository: ReminderRepository,
        billRepository: BillRepository,
        foodJournalRepository: FoodJournalRepository,
        mediaAssetRepository: MediaAssetRepository,
        mediaAttachmentRepository: MediaAttachmentRepository,
        diaryRepository: DiaryRepository,
        anniversaryRepository: AnniversaryRepository,
        historyRepository: HistoryRepository,
        dataTransferService: AppDataTransferService,
        notificationScheduler: any AppNotificationScheduling,
        reminderNotificationService: ReminderNotificationService,
        anniversaryNotificationService: AnniversaryNotificationService,
        locationTrackerViewModel: LocationTrackerViewModel,
        featureVisibilitySettings: FeatureVisibilitySettings,
        appPreferences: AppPreferences
    ) {
        self.database = database
        self.syncEngine = syncEngine
        self.deviceIdentifier = deviceIdentifier
        self.locationService = locationService
        self.trackRepository = trackRepository
        self.decisionRepository = decisionRepository
        self.itemLocatorRepository = itemLocatorRepository
        self.reminderRepository = reminderRepository
        self.billRepository = billRepository
        self.foodJournalRepository = foodJournalRepository
        self.mediaAssetRepository = mediaAssetRepository
        self.mediaAttachmentRepository = mediaAttachmentRepository
        self.diaryRepository = diaryRepository
        self.anniversaryRepository = anniversaryRepository
        self.historyRepository = historyRepository
        self.dataTransferService = dataTransferService
        self.notificationScheduler = notificationScheduler
        self.reminderNotificationService = reminderNotificationService
        self.anniversaryNotificationService = anniversaryNotificationService
        self.locationTrackerViewModel = locationTrackerViewModel
        self.featureVisibilitySettings = featureVisibilitySettings
        self.appPreferences = appPreferences
    }

    /// App 启动入口。数据库初始化失败时由 App 层展示恢复界面。
    static func bootstrap() throws -> AppContainer {
        let deviceIdentifier = DeviceIdentifier()
        let database = try AppDatabase.makeDefault()
        try database.verifyIntegrity()
        return try makeContainer(
            database: database,
            deviceIdentifier: deviceIdentifier,
            featureVisibilitySettings: FeatureVisibilitySettings(),
            appPreferences: AppPreferences()
        )
    }

    func prepareForFullDataReset() {
        locationTrackerViewModel.finishForAppTermination()
        try? database.close()
    }

    func makeHistoryViewModel() -> HistoryViewModel {
        HistoryViewModel(repository: historyRepository)
    }

    func makeTrackHistoryViewModel() -> TrackHistoryViewModel {
        TrackHistoryViewModel(
            repository: trackRepository,
            syncEngine: syncEngine
        )
    }

    func makeDecisionChoiceHistoryViewModel() -> DecisionChoiceHistoryViewModel {
        DecisionChoiceHistoryViewModel(
            repository: decisionRepository,
            syncEngine: syncEngine
        )
    }

    func makeItemLocatorViewModel() -> ItemLocatorViewModel {
        ItemLocatorViewModel(
            repository: itemLocatorRepository,
            syncEngine: syncEngine
        )
    }

    func makeRemindersViewModel() -> RemindersViewModel {
        RemindersViewModel(
            repository: reminderRepository,
            notificationService: reminderNotificationService,
            syncEngine: syncEngine
        )
    }

    func makeBillsViewModel() -> BillsViewModel {
        BillsViewModel(
            repository: billRepository,
            syncEngine: syncEngine
        )
    }

    func makeFoodJournalViewModel() -> FoodJournalViewModel {
        FoodJournalViewModel(
            repository: foodJournalRepository,
            syncEngine: syncEngine
        )
    }

    func makeDiaryViewModel() -> DiaryViewModel {
        DiaryViewModel(
            repository: diaryRepository,
            syncEngine: syncEngine
        )
    }

    func makeAnniversariesViewModel() -> AnniversariesViewModel {
        AnniversariesViewModel(
            repository: anniversaryRepository,
            notificationService: anniversaryNotificationService,
            syncEngine: syncEngine
        )
    }

    func makeStatisticsViewModel() -> StatisticsViewModel {
        StatisticsViewModel(
            trackRepository: trackRepository,
            decisionRepository: decisionRepository,
            itemLocatorRepository: itemLocatorRepository,
            reminderRepository: reminderRepository,
            billRepository: billRepository,
            foodJournalRepository: foodJournalRepository,
            diaryRepository: diaryRepository,
            anniversaryRepository: anniversaryRepository
        )
    }

    /// 预览环境使用临时内存数据库，避免污染真实数据文件。
    static var preview: AppContainer {
        do {
            let deviceIdentifier = DeviceIdentifier(defaults: .standard, key: "preview_device_id")
            let database = try AppDatabase.makeInMemory()
            return try makeContainer(
                database: database,
                deviceIdentifier: deviceIdentifier,
                featureVisibilitySettings: FeatureVisibilitySettings(defaults: nil),
                appPreferences: AppPreferences(defaults: nil)
            )
        } catch {
            fatalError("Preview AppContainer 初始化失败: \(error)")
        }
    }

    private static func makeContainer(
        database: AppDatabase,
        deviceIdentifier: DeviceIdentifier,
        featureVisibilitySettings: FeatureVisibilitySettings,
        appPreferences: AppPreferences
    ) throws -> AppContainer {
        let changeLogRepository = ChangeLogRepository(database: database)
        let syncEngine = NoopSyncEngine(changeLogRepository: changeLogRepository)
        let trackRepository = TrackRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let decisionRepository = DecisionRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let itemLocatorRepository = ItemLocatorRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let reminderRepository = ReminderRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let billRepository = BillRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let mediaAssetRepository = MediaAssetRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let mediaAttachmentRepository = MediaAttachmentRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let foodJournalRepository = FoodJournalRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            mediaAttachmentRepository: mediaAttachmentRepository,
            deviceIdentifier: deviceIdentifier
        )
        let diaryRepository = DiaryRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            mediaAttachmentRepository: mediaAttachmentRepository,
            deviceIdentifier: deviceIdentifier
        )
        let anniversaryRepository = AnniversaryRepository(
            database: database,
            changeLogRepository: changeLogRepository,
            deviceIdentifier: deviceIdentifier
        )
        let historyRepository = HistoryRepository(database: database)
        let dataTransferService = AppDataTransferService(database: database)
        let notificationScheduler = LocalNotificationScheduler()
        let reminderNotificationService = ReminderNotificationService(scheduler: notificationScheduler)
        let anniversaryNotificationService = AnniversaryNotificationService(scheduler: notificationScheduler)
        let locationService = LocationService()
        let locationTrackerViewModel = LocationTrackerViewModel(
            repository: trackRepository,
            locationService: locationService,
            syncEngine: syncEngine
        )

        return AppContainer(
            database: database,
            syncEngine: syncEngine,
            deviceIdentifier: deviceIdentifier,
            locationService: locationService,
            trackRepository: trackRepository,
            decisionRepository: decisionRepository,
            itemLocatorRepository: itemLocatorRepository,
            reminderRepository: reminderRepository,
            billRepository: billRepository,
            foodJournalRepository: foodJournalRepository,
            mediaAssetRepository: mediaAssetRepository,
            mediaAttachmentRepository: mediaAttachmentRepository,
            diaryRepository: diaryRepository,
            anniversaryRepository: anniversaryRepository,
            historyRepository: historyRepository,
            dataTransferService: dataTransferService,
            notificationScheduler: notificationScheduler,
            reminderNotificationService: reminderNotificationService,
            anniversaryNotificationService: anniversaryNotificationService,
            locationTrackerViewModel: locationTrackerViewModel,
            featureVisibilitySettings: featureVisibilitySettings,
            appPreferences: appPreferences
        )
    }
}
