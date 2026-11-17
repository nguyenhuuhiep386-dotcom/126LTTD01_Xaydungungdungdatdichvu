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
import vn.servio.partner.data.remote.FileApi
import vn.servio.partner.data.remote.PartnerApi
import vn.servio.partner.data.repository.FakeFeedRepository
import vn.servio.partner.data.repository.FeedRepository
import vn.servio.partner.data.repository.FileRepository
import vn.servio.partner.data.repository.PartnerProfileRepository
import vn.servio.partner.data.repository.RemoteFileRepository
import vn.servio.partner.data.repository.RemotePartnerProfileRepository
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
}

/** Partner repositories. Swap Fake → Remote here once the endpoint is ready on Swagger. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {

    @Binds
    abstract fun bindFeedRepository(impl: FakeFeedRepository): FeedRepository

    @Binds
    abstract fun bindPartnerProfileRepository(impl: RemotePartnerProfileRepository): PartnerProfileRepository

    @Binds
    abstract fun bindFileRepository(impl: RemoteFileRepository): FileRepository
}
