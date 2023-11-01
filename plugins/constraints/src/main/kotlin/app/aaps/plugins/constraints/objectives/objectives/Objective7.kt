package app.aaps.plugins.constraints.objectives.objectives

import app.aaps.core.interfaces.utils.T
import app.aaps.plugins.constraints.R
import dagger.android.HasAndroidInjector

class Objective7(injector: HasAndroidInjector) : Objective(injector, "autosens", R.string.objectives_autosens_objective, R.string.objectives_autosens_gate) {

    init {
        tasks.add(
            MinimumDurationTask(this, 5) //Era T.days(7).msecs(), cambiato per ovvi motivi
                .learned(Learned(R.string.objectives_autosens_learned))
        )
    }
}