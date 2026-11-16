package vn.servio.partner.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import vn.servio.core.data.model.AppFlavor
import vn.servio.core.data.model.Me
import vn.servio.core.data.model.PartnerVerificationStatus
import vn.servio.core.utils.AppConfig
import vn.servio.core.utils.AuthRouter
import vn.servio.partner.BuildConfig
import vn.servio.partner.R
import vn.servio.partner.data.remote.FeedApi
import vn.servio.partner.data.repository.AccountApi
import vn.servio.partner.data.repository.ConsentApi
import vn.servio.partner.data.repository.ConsentRepository
import vn.servio.partner.data.repository.RemoteConsentRepository
import vn.servio.partner.data.repository.AccountRepository
import vn.servio.partner.data.repository.RemoteAccountRepository
import vn.servio.partner.data.remote.FileApi
import vn.servio.partner.data.remote.OrderApi
import vn.servio.partner.data.remote.PartnerApi
import vn.servio.partner.data.remote.PresenceApi
import vn.servio.partner.data.repository.FeedRepository
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.OrderRepository
import vn.servio.partner.data.repository.PartnerProfileRepository
import vn.servio.partner.data.repository.PresenceRepository
import vn.servio.partner.data.repository.RemoteFeedRepository
import vn.servio.partner.data.repository.RemoteFileRepository
import vn.servio.partner.data.repository.RemoteOrderRepository
import vn.servio.partner.data.repository.RemotePartnerProfileRepository
import vn.servio.partner.data.repository.RemotePresenceRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppConfig() = AppConfig(AppFlavor.PARTNER, BuildConfig.VERSION_NAME)

    @Provides
    fun provideAuthRouter() = AuthRouter(::destinationAfterLogin)

    /** After login: signup (PS-02) until KYC is submitted, PS-06 while pending/rejected, main tabs when approved. */
    fun destinationAfterLogin(user: Me): Int = when (user.partnerProfile?.verificationStatus) {
        PartnerVerificationStatus.APPROVED -> vn.servio.core.R.id.main_graph
        PartnerVerificationStatus.PENDING, PartnerVerificationStatus.REJECTED -> R.id.ps06
        PartnerVerificationStatus.NOT_SUBMITTED, null -> R.id.ps02
    }

    @Provides
    @Singleton
    fun providePartnerApi(retrofit: Retrofit): PartnerApi = retrofit.create(PartnerApi::class.java)

    @Provides
    @Singleton
    fun provideFileApi(retrofit: Retrofit): FileApi = retrofit.create(FileApi::class.java)

    @Provides
    @Singleton
    fun provideFeedApi(retrofit: Retrofit): FeedApi = retrofit.create(FeedApi::class.java)

    @Provides
    @Singleton
    fun providePresenceApi(retrofit: Retrofit): PresenceApi = retrofit.create(PresenceApi::class.java)

    @Provides
    @Singleton
    fun provideOrderApi(retrofit: Retrofit): OrderApi = retrofit.create(OrderApi::class.java)

    @Provides
    @Singleton
    fun provideAccountApi(retrofit: Retrofit): AccountApi = retrofit.create(AccountApi::class.java)

    @Provides
    @Singleton
    fun provideConsentApi(retrofit: Retrofit): ConsentApi = retrofit.create(ConsentApi::class.java)
}

/** Partner repositories. Swap Fake → Remote here once the endpoint is ready on Swagger. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {

    @Binds
    abstract fun bindFeedRepository(impl: RemoteFeedRepository): FeedRepository

    @Binds
    abstract fun bindPresenceRepository(impl: RemotePresenceRepository): PresenceRepository

    @Binds
    abstract fun bindPartnerProfileRepository(impl: RemotePartnerProfileRepository): PartnerProfileRepository

    @Binds
    abstract fun bindFileRepository(impl: RemoteFileRepository): FileRepository

    @Binds
    abstract fun bindOrderRepository(impl: RemoteOrderRepository): OrderRepository

    @Binds
    abstract fun bindAccountRepository(impl: RemoteAccountRepository): AccountRepository

    @Binds
    abstract fun bindConsentRepository(impl: RemoteConsentRepository): ConsentRepository
}
