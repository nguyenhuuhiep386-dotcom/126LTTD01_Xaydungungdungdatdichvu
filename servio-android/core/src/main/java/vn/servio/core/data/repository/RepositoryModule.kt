package vn.servio.core.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds shared repository interfaces to their implementations. Switch Fake ↔ Remote here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindAuthRepository(impl: RemoteAuthRepository): AuthRepository

    @Binds
    abstract fun bindCategoryRepository(impl: RemoteCategoryRepository): CategoryRepository
}
