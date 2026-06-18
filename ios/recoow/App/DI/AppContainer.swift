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
    @ObservationIgnored let notificationScheduler: any AppNotificationScheduling
    @ObservationIgnored let reminderNotificationService: ReminderNotificationService
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
        notificationScheduler: any AppNotificationScheduling,
        reminderNotificationService: ReminderNotificationService,
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
        self.notificationScheduler = notificationScheduler
        self.reminderNotificationService = reminderNotificationService
        self.locationTrackerViewModel = locationTrackerViewModel
        self.featureVisibilitySettings = featureVisibilitySettings
        self.appPreferences = appPreferences
    }

    /// App 启动入口。数据库初始化失败属于不可恢复配置错误，直接暴露给开发阶段。
    static func bootstrap() -> AppContainer {
        do {
            let deviceIdentifier = DeviceIdentifier()
            let database = try AppDatabase.makeDefault()
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
            let notificationScheduler = LocalNotificationScheduler()
            let reminderNotificationService = ReminderNotificationService(scheduler: notificationScheduler)
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
                notificationScheduler: notificationScheduler,
                reminderNotificationService: reminderNotificationService,
                locationTrackerViewModel: locationTrackerViewModel,
                featureVisibilitySettings: FeatureVisibilitySettings(),
                appPreferences: AppPreferences()
            )
        } catch {
            fatalError("AppContainer 初始化失败: \(error)")
        }
    }

    /// 预览环境使用临时内存数据库，避免污染真实数据文件。
    static var preview: AppContainer {
        do {
            let deviceIdentifier = DeviceIdentifier(defaults: .standard, key: "preview_device_id")
            let database = try AppDatabase.makeInMemory()
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
            let notificationScheduler = LocalNotificationScheduler()
            let reminderNotificationService = ReminderNotificationService(scheduler: notificationScheduler)
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
                notificationScheduler: notificationScheduler,
                reminderNotificationService: reminderNotificationService,
                locationTrackerViewModel: locationTrackerViewModel,
                featureVisibilitySettings: FeatureVisibilitySettings(defaults: nil),
                appPreferences: AppPreferences(defaults: nil)
            )
        } catch {
            fatalError("Preview AppContainer 初始化失败: \(error)")
        }
    }
}
