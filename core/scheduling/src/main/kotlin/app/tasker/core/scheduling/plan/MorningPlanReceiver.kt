package app.tasker.core.scheduling.plan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tasker.core.scheduling.AlarmKind
import app.tasker.core.scheduling.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Morning plan alarm → expedited [MorningPlanWorker] (tech plan §12.3); the receiver itself does no work. */
@AndroidEntryPoint
class MorningPlanReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: Scheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmKind.MORNING_PLAN.action) return
        scheduler.enqueueMorningPlan()
    }
}
