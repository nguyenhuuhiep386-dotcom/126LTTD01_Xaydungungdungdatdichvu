package vn.servio.customer.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import vn.servio.core.data.model.AppFlavor
import vn.servio.core.utils.AppConfig
import vn.servio.core.utils.AuthRouter
import vn.servio.customer.BuildConfig
import vn.servio.customer.data.image.CameraCaptureFiles
import vn.servio.customer.data.image.CaptureFiles
import vn.servio.customer.data.remote.AddressApi
import vn.servio.customer.data.remote.FileApi
import vn.servio.customer.data.remote.DisputeApi
import vn.servio.customer.data.remote.NotificationApi
import vn.servio.customer.data.remote.OrderApi
import vn.servio.customer.data.remote.PartnerApi
import vn.servio.customer.data.remote.ServerConfigApi
import vn.servio.customer.data.remote.ServiceRequestApi
import vn.servio.customer.data.remote.WalletApi
import vn.servio.customer.data.repository.AddressRepository
import vn.servio.customer.data.repository.ServerConfigRepository
import vn.servio.customer.data.repository.RemoteServerConfigRepository
import vn.servio.customer.data.repository.CreateRequestRepository
import vn.servio.customer.data.repository.FileRepository
import vn.servio.customer.data.repository.OrderRepository
import vn.servio.customer.data.repository.RemoteOrderRepository
import vn.servio.customer.data.repository.RemoteReviewRepository
import vn.servio.customer.data.repository.ReviewRepository
import vn.servio.customer.data.repository.PartnerRepository
import vn.servio.customer.data.repository.ProfileRepository
import vn.servio.customer.data.repository.RemoteAddressRepository
import vn.servio.customer.data.repository.RemoteCreateRequestRepository
import vn.servio.customer.data.repository.RemoteFileRepository
import vn.servio.customer.data.repository.RemotePartnerRepository
import vn.servio.customer.data.repository.RemoteProfileRepository
import vn.servio.customer.data.repository.HubOrderUpdates
import vn.servio.customer.data.repository.HubQuoteUpdates
import vn.servio.customer.data.repository.OrderUpdates
import vn.servio.customer.data.repository.QuoteUpdates
import vn.servio.customer.data.repository.RemoteServiceRequestRepository
import vn.servio.customer.data.repository.ServiceRequestRepository
import vn.servio.customer.data.repository.CustomerSessionCleanup
import vn.servio.customer.data.repository.DisputeRepository
import vn.servio.customer.data.repository.NotificationRepository
import vn.servio.customer.data.repository.RemoteDisputeRepository
import vn.servio.customer.data.repository.RemoteNotificationRepository
import vn.servio.customer.data.repository.RemoteWalletRepository
import vn.servio.customer.data.repository.SessionCleanup
import vn.servio.customer.data.repository.WalletRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppConfig() = AppConfig(AppFlavor.CUSTOMER, BuildConfig.VERSION_NAME)

    /** After login: CS-05 when the name is missing, otherwise the main tabs. */
    @Provides
    fun provideAuthRouter() = AuthRouter { user ->
        if (user.needsProfileCompletion) vn.servio.core.R.id.completeProfileFragment else vn.servio.core.R.id.main_graph
    }

    @Provides
    @Singleton
    fun provideAddressApi(retrofit: Retrofit): AddressApi = retrofit.create(AddressApi::class.java)

    @Provides
    @Singleton
    fun provideFileApi(retrofit: Retrofit): FileApi = retrofit.create(FileApi::class.java)

    @Provides
    @Singleton
    fun provideServiceRequestApi(retrofit: Retrofit): ServiceRequestApi = retrofit.create(ServiceRequestApi::class.java)

    @Provides
    @Singleton
    fun providePartnerApi(retrofit: Retrofit): PartnerApi = retrofit.create(PartnerApi::class.java)

    @Provides
    @Singleton
    fun provideOrderApi(retrofit: Retrofit): OrderApi = retrofit.create(OrderApi::class.java)

    @Provides
    @Singleton
    fun provideWalletApi(retrofit: Retrofit): WalletApi = retrofit.create(WalletApi::class.java)

    @Provides
    @Singleton
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi = retrofit.create(NotificationApi::class.java)

    @Provides
    @Singleton
    fun provideServerConfigApi(retrofit: Retrofit): ServerConfigApi = retrofit.create(ServerConfigApi::class.java)

    @Provides
    @Singleton
    fun provideDisputeApi(retrofit: Retrofit): DisputeApi = retrofit.create(DisputeApi::class.java)
}

/** Customer repositories. All of them call the real API. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {

    @Binds
    abstract fun bindWalletRepository(impl: RemoteWalletRepository): WalletRepository

    @Binds
    abstract fun bindNotificationRepository(impl: RemoteNotificationRepository): NotificationRepository

    @Binds
    abstract fun bindServerConfigRepository(impl: RemoteServerConfigRepository): ServerConfigRepository

    @Binds
    abstract fun bindDisputeRepository(impl: RemoteDisputeRepository): DisputeRepository

    @Binds
    abstract fun bindSessionCleanup(impl: CustomerSessionCleanup): SessionCleanup

    @Binds
    abstract fun bindCaptureFiles(impl: CameraCaptureFiles): CaptureFiles

    @Binds
    abstract fun bindServiceRequestRepository(impl: RemoteServiceRequestRepository): ServiceRequestRepository

    @Binds
    abstract fun bindProfileRepository(impl: RemoteProfileRepository): ProfileRepository

    @Binds
    abstract fun bindAddressRepository(impl: RemoteAddressRepository): AddressRepository

    @Binds
    abstract fun bindFileRepository(impl: RemoteFileRepository): FileRepository

    @Binds
    abstract fun bindCreateRequestRepository(impl: RemoteCreateRequestRepository): CreateRequestRepository

    @Binds
    abstract fun bindPartnerRepository(impl: RemotePartnerRepository): PartnerRepository

    @Binds
    abstract fun bindOrderRepository(impl: RemoteOrderRepository): OrderRepository

    @Binds
    abstract fun bindReviewRepository(impl: RemoteReviewRepository): ReviewRepository

    @Binds
    abstract fun bindQuoteUpdates(impl: HubQuoteUpdates): QuoteUpdates

    @Binds
    abstract fun bindOrderUpdates(impl: HubOrderUpdates): OrderUpdates
}
