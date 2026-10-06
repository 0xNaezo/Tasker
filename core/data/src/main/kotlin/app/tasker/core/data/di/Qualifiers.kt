package app.tasker.core.data.di

import javax.inject.Qualifier

/** Coroutine scope that lives as long as the application: post-commit effects and background catch-up. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
