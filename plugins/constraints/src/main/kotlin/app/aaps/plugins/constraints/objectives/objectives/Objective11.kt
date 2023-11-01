package app.aaps.plugins.constraints.objectives.objectives

import app.aaps.core.interfaces.utils.T
import app.aaps.plugins.constraints.R
import dagger.android.HasAndroidInjector

class Objective11(injector: HasAndroidInjector) : Objective(injector, "dyn_isf", R.string.objectives_dyn_isf_objective, R.string.objectives_dyn_isf_gate) {

    init {
        tasks.add(
            MinimumDurationTask(this, 5) //Era T.days(28).msecs(), cambiato per ovvi motivi
                .learned(Learned(R.string.objectives_dyn_isf_learned))
        )
    }
}