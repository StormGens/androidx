/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.camera2.pipe.testing

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * A [RobolectricTestRunner] for [androidx.camera.camera2.pipe.testing] unit tests.
 *
 * It has instrumentation turned off for the [androidx.camera.camera2.pipe.testing] package.
 *
 * Robolectric tries to instrument Kotlin classes, and it throws errors when it encounters
 * companion objects, constructors with default values for parameters, and data classes with
 * inline classes. We don't need shadowing of our classes because we want to use the actual
 * objects in our tests.
 */
public class CameraPipeRobolectricTestRunner(testClass: Class<*>) :
    RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod?): InstrumentationConfiguration {
        val builder = InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
        builder.doNotInstrumentPackage("androidx.camera.camera2.pipe")
        builder.doNotInstrumentPackage("androidx.camera.camera2.pipe.testing")
        return builder.build()
    }
}