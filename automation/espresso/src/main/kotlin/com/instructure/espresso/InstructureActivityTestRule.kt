/*
 * Copyright (C) 2018 - present Instructure, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package com.instructure.espresso

import android.app.Activity
import android.content.Context
import android.support.test.InstrumentationRegistry
import android.support.test.rule.FinishingActivityTestRule

abstract class InstructureActivityTestRule<T : Activity>(activityClass: Class<T>) : FinishingActivityTestRule<T>(activityClass) {

    abstract fun performReset(context: Context)

    override fun beforeActivityLaunched() {
        loopMainThreadUntilIdle()
        finishAllActivities()
        performReset(InstrumentationRegistry.getTargetContext())
    }

    override fun afterActivityLaunched() {
        loopMainThreadUntilIdle()
    }

    private fun loopMainThreadUntilIdle() {
        if (UiControllerSingleton.exists()) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { UiControllerSingleton.get()?.loopMainThreadUntilIdle() }
        }
    }

}
