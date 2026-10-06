package app.tasker

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * What the app's own Application does on a phone: WorkManager with a configuration (the default initializer is
 * removed), and notifications allowed so no system dialog covers the screens under test.
 */
internal fun e2eEnvironment(): TestRule = RuleChain
    .outerRule(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        },
    )
    .around(
        object : ExternalResource() {
            override fun before() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                WorkManagerTestInitHelper.initializeTestWorkManager(
                    context,
                    Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
                )
            }
        },
    )
