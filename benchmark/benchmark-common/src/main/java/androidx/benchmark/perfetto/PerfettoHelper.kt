/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.benchmark.perfetto

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RestrictTo
import androidx.benchmark.DeviceInfo.deviceSummaryString
import androidx.benchmark.Shell
import androidx.benchmark.userspaceTrace
import androidx.test.platform.app.InstrumentationRegistry
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.io.IOException

/**
 * PerfettoHelper is used to start and stop the perfetto tracing and move the
 * output perfetto trace file to destination folder.
 *
 * @suppress
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@RequiresApi(21)
public class PerfettoHelper(
    private val unbundled: Boolean = Build.VERSION.SDK_INT < LOWEST_BUNDLED_VERSION_SUPPORTED
) {
    init {
        require(unbundled || Build.VERSION.SDK_INT >= LOWEST_BUNDLED_VERSION_SUPPORTED) {
            "Perfetto capture using the os version of perfetto requires API " +
                "$LOWEST_BUNDLED_VERSION_SUPPORTED or greater."
        }
    }

    var perfettoPid: Int? = null

    private fun perfettoStartupException(label: String, cause: Exception?): IllegalStateException {
        return IllegalStateException(
            """
            $label
            Please report a bug, and include a logcat capture of the test run and failure.
            $deviceSummaryString
            """.trimIndent(),
            cause
        )
    }

    /**
     * Start the perfetto tracing in background using the given config file.
     *
     * The output will be written to /data/misc/perfetto-traces/trace_output.pb. Perfetto has
     * write access only to /data/misc/perfetto-traces/ folder. The config file may be anywhere
     * readable by shell.
     *
     * @param configFilePath used for collecting the perfetto trace.
     * @param isTextProtoConfig true if the config file is textproto format otherwise false.
     */
    public fun startCollecting(configFilePath: String, isTextProtoConfig: Boolean) {
        require(configFilePath.isNotEmpty()) {
            "Perfetto config cannot be empty."
        }

        require(perfettoPid == null) {
            "Perfetto instance is already running"
        }

        try {
            // Cleanup already existing perfetto process.
            Log.i(LOG_TAG, "Cleanup perfetto before starting.")
            stopAllPerfettoProcesses()

            // The actual location of the config path.
            val actualConfigPath = if (unbundled) {
                val path = "$UNBUNDLED_PERFETTO_ROOT_DIR/config.pb"
                // Move the config to a directory that unbundled perfetto has permissions for.
                Shell.executeCommand("rm $path")
                Shell.executeCommand("mv $configFilePath $path")
                path
            } else {
                configFilePath
            }

            val outputPath = getPerfettoTmpOutputFilePath()
            // Remove already existing temporary output trace file if any.
            val output = Shell.executeCommand("rm $outputPath")
            Log.i(LOG_TAG, "Perfetto output file cleanup - $output")

            // Perfetto
            val perfettoCmd = perfettoCommand(actualConfigPath, isTextProtoConfig)
            Log.i(LOG_TAG, "Starting perfetto tracing with cmd: $perfettoCmd")
            val perfettoCmdOutput = Shell.executeScript(perfettoCmd).trim()
            Log.i(LOG_TAG, "Perfetto pid - $perfettoCmdOutput")
            perfettoPid = perfettoCmdOutput.toInt()
        } catch (ioe: IOException) {
            throw perfettoStartupException("Unable to start perfetto tracing", ioe)
        }

        if (!isRunning()) {
            throw perfettoStartupException("Perfetto tracing failed to start.", null)
        }
        Log.i(LOG_TAG, "Perfetto tracing started successfully with pid $perfettoPid.")
    }

    /**
     * Check if this PerfettoHelper's perfetto process is running or not.
     *
     * @return true if perfetto is running otherwise false.
     */
    public fun isRunning(): Boolean {
        return perfettoPid?.let {
            Shell.isProcessAlive(it, perfettoProcessName)
        } ?: false
    }

    /**
     * Stop the perfetto trace collection under /data/misc/perfetto-traces/trace_output.pb after
     * waiting for given time in msecs and copy the output to the destination file.
     *
     * @param waitTimeInMsecs time to wait in msecs before stopping the trace collection.
     * @param destinationFile file to copy the perfetto output trace.
     * @return true if the trace collection is successful otherwise false.
     */
    public fun stopCollecting(waitTimeInMsecs: Long, destinationFile: String) {
        // Wait for the dump interval before stopping the trace.
        userspaceTrace("Wait for perfetto flush") {
            Log.i(LOG_TAG, "Waiting for $waitTimeInMsecs millis before stopping perfetto.")
            SystemClock.sleep(waitTimeInMsecs)
        }

        // Stop the perfetto and copy the output file.
        Log.i(LOG_TAG, "Stopping perfetto.")

        userspaceTrace("stop perfetto process") {
            stopPerfetto()
        }

        Log.i(LOG_TAG, "Writing to $destinationFile.")
        userspaceTrace("copy trace to output dir") {
            copyFileOutput(destinationFile)
        }
    }

    /**
     * Utility method for stopping perfetto.
     *
     * @return true if perfetto is stopped successfully.
     */
    private fun stopPerfetto() {
        val pid = perfettoPid

        require(pid != null)
        Shell.terminateProcessesAndWait(
            waitPollPeriodMs = PERFETTO_KILL_WAIT_TIME_MS,
            waitPollMaxCount = PERFETTO_KILL_WAIT_COUNT,
            Shell.ProcessPid(
                pid = pid,
                processName = perfettoProcessName
            )
        )
        perfettoPid = null
    }

    /**
     * @return the shell command that can be used to start Perfetto.
     */
    private fun perfettoCommand(configFilePath: String, isTextProtoConfig: Boolean): String {
        val outputPath = getPerfettoTmpOutputFilePath()
        var command = if (!unbundled) (
            // Bundled perfetto reads configuration from stdin.
            "cat $configFilePath | perfetto --background -c - -o $outputPath"
            ) else {
            // Unbundled perfetto can read configuration from a file that it has permissions to
            // read from. This because it assumes the identity of the shell and therefore has
            // access to /data/local/tmp directory.
            "$unbundledPerfettoShellPath --background" +
                " -c $configFilePath" +
                " -o $outputPath"
        }

        if (isTextProtoConfig) {
            command += PERFETTO_TXT_PROTO_ARG
        }
        return command
    }

    /**
     * @return the [String] path to the temporary output file used to store the trace file
     * during collection.
     */
    private fun getPerfettoTmpOutputFilePath(): String {
        return if (unbundled) {
            UNBUNDLED_TEMP_OUTPUT_FILE
        } else {
            PERFETTO_TMP_OUTPUT_FILE
        }
    }

    /**
     * Copy the temporary perfetto trace output file from /data/local/tmp/trace_output.pb to given
     * destinationFile.
     *
     * @param destinationFile file to copy the perfetto output trace.
     * @return true if the trace file copied successfully otherwise false.
     */
    private fun copyFileOutput(destinationFile: String): Boolean {
        val sourceFile = getPerfettoTmpOutputFilePath()
        val filePath = File(destinationFile)
        val destDirectory = filePath.parent
        if (destDirectory != null) {
            // Check if the directory already exists
            val directory = File(destDirectory)
            if (!directory.exists()) {
                val success = directory.mkdirs()
                if (!success) {
                    Log.e(
                        LOG_TAG,
                        "Result output directory $destDirectory not created successfully."
                    )
                    return false
                }
            }
        }

        // Copy the collected trace from /data/misc/perfetto-traces/trace_output.pb to
        // destinationFile
        try {
            val moveResult =
                Shell.executeCommand("mv $sourceFile $destinationFile")
            if (moveResult.isNotEmpty()) {
                Log.e(
                    LOG_TAG,
                    """
                        Unable to move perfetto output file from $sourceFile
                        to $destinationFile due to $moveResult.
                    """.trimIndent()
                )
                return false
            }
        } catch (ioe: IOException) {
            Log.e(
                LOG_TAG,
                "Unable to move the perfetto trace file to destination file.",
                ioe
            )
            return false
        }
        return true
    }

    // Perfetto executable
    private val perfettoProcessName = if (unbundled) "tracebox" else "perfetto"

    companion object {
        internal const val LOG_TAG = "PerfettoCapture"

        const val LOWEST_BUNDLED_VERSION_SUPPORTED = 29

        // Command to start the perfetto tracing in the background.
        // perfetto --background -c /data/misc/perfetto-traces/trace_config.pb -o
        // /data/misc/perfetto-traces/trace_output.pb
        private const val PERFETTO_TMP_OUTPUT_FILE = "/data/misc/perfetto-traces/trace_output.pb"

        // Additional arg to indicate that the perfetto config file is text format.
        private const val PERFETTO_TXT_PROTO_ARG = " --txt"

        // Max wait count for checking if perfetto is stopped successfully
        private const val PERFETTO_KILL_WAIT_COUNT = 30

        // Check if perfetto is stopped every 100 millis.
        private const val PERFETTO_KILL_WAIT_TIME_MS: Long = 500

        // Path where unbundled tracebox is copied to
        private const val UNBUNDLED_PERFETTO_ROOT_DIR = "/data/local/tmp"

        private const val UNBUNDLED_TEMP_OUTPUT_FILE =
            "$UNBUNDLED_PERFETTO_ROOT_DIR/trace_output.pb"

        // A set of supported ABIs
        private val SUPPORTED_64_ABIS = setOf("arm64-v8a", "x86_64")
        private val SUPPORTED_32_ABIS = setOf("armeabi")

        @TestOnly
        fun isAbiSupported(): Boolean {
            Log.d(LOG_TAG, "Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            // Cuttlefish is x86 but claims support for x86_64
            return !Build.MODEL.contains("Cuttlefish") && ( // b/180022458
                Build.SUPPORTED_64_BIT_ABIS.any { SUPPORTED_64_ABIS.contains(it) } ||
                    Build.SUPPORTED_32_BIT_ABIS.any { SUPPORTED_32_ABIS.contains(it) }
                )
        }

        @get:TestOnly
        val unbundledPerfettoShellPath: String by lazy {
            createExecutable("tracebox")
        }

        fun createExecutable(tool: String): String {
            userspaceTrace("create executable: $tool") {
                if (!isAbiSupported()) {
                    throw IllegalStateException(
                        "Unsupported ABI (${Build.SUPPORTED_ABIS.joinToString()})"
                    )
                }
                val suffix = when {
                    // The order is important because `SUPPORTED_64_BIT_ABIS` lists all ABI
                    // supported by a device. That is why we need to search from most specific to
                    // least specific. For e.g. emulators claim to support aarch64, when in reality
                    // they can only support x86 or x86_64.
                    Build.SUPPORTED_64_BIT_ABIS.any { it.startsWith("x86_64") } -> "x86_64"
                    Build.SUPPORTED_64_BIT_ABIS.any { it.startsWith("arm64") } -> "aarch64"
                    Build.SUPPORTED_32_BIT_ABIS.any { it.startsWith("armeabi") } -> "arm"
                    else -> IllegalStateException(
                        // Perfetto does not support x86 binaries
                        "Unsupported ABI (${Build.SUPPORTED_ABIS.joinToString()})"
                    )
                }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val inputStream = instrumentation.context.assets.open("${tool}_$suffix")
                return Shell.createRunnableExecutable(tool, inputStream)
            }
        }

        public fun stopAllPerfettoProcesses() {
            listOf("perfetto", "tracebox").forEach { processName ->
                Shell.terminateProcessesAndWait(
                    waitPollPeriodMs = PERFETTO_KILL_WAIT_TIME_MS,
                    waitPollMaxCount = PERFETTO_KILL_WAIT_COUNT,
                    processName
                )
            }
        }
    }
}
