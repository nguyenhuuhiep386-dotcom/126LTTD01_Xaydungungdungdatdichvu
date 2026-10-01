package vn.servio.customer.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import vn.servio.core.data.model.AppFlavor
import vn.servio.core.utils.AppConfig
import vn.servio.core.utils.AuthRouter
import vn.servio.customer.BuildConfig
import vn.servio.customer.data.repository.FakeServiceRequestRepository
import vn.servio.customer.data.repository.ServiceRequestRepository
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
}

/** Customer repositories. Swap Fake → Remote here once the endpoint is ready on Swagger. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {

    @Binds
    abstract fun bindServiceRequestRepository(impl: FakeServiceRequestRepository): ServiceRequestRepository
}
