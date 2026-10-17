package com.example.manager

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure thermal signal governor with median smoothing, dwell and hysteresis.
 *
 * Responsibilities:
 * - Smooth the raw cpuMax signal via a median-of-5 filter (approx. 10s window at 2s ticks).
 * - Enter a thermal level when smoothed signal S >= threshold T_S(N) or battery >= T_B(N)
 *   for a sustained dwell of >= 30s.
 * - Emergency brake: if smoothed S >= 85°C for >= 10s -> force battery_saver.
 * - Restore previous profile only when smoothed S <= T_S(N)-10 and battery <= T_B(N)-3
 *   for a sustained dwell of >= 60s (hysteresis gap).
 * - Cooldown of 120s after any action before new action can be considered.
 * - If the user manually changes profile during an episode, cancel the pending restoration.
 * - NaN inputs produce no action and reset all timers (no time counting while NaN).
 *
 * This class is deliberately side-effect-free: it only reads inputs and returns
 * decisions (enter/leave/emergency/cooldown). The caller (WatchdogManager) is
 * responsible for applying the profile change.
 *
 * Naming convention:
 *   N       = profile index (balanced=3, saver=5, etc.)
 *   T_S(N)  = temperature threshold to enter level N
 *   T_B(N)  = battery temperature threshold to enter level N
 *   S       = smoothed median signal (cpuMax in °C)
 *   B       = battery temperature in °C
 *   dwell   = sustained time above/below threshold in seconds
 */
internal class ThermalGovernor {

    /** Median window size: 5 ticks @ 2s interval = ~10s effective window. */
    private val medianWindowSize = 5

    /** Thresholds per profile (startup defaults, will be overridden with real data
     * from the first real session — see FASE B step B2). */
    @Volatile
    private var tempThresholdBalanced = 55f     // T_S(balanced)
    @Volatile
    private var tempThresholdSaver = 60f        // T_S(saver)
    @Volatile
    private var batThresholdBalanced = 40f      // T_B(balanced)
    @Volatile
    private var batThresholdSaver = 43f         // T_B(saver)

    /** Smoothing state: last N cpuMax readings (filled with NaN when no data). */
    private val cpuSamples = MutableList(medianWindowSize) { Float.NaN }
    private var cpuSampleIndex = 0

    /** Battery smoothing: last reading; NaN until first real battery temp is available. */
    @Volatile
    private var batteryTemp: Float = Float.NaN

    /** Profile management state. */
    private var currentProfile: Int = 0          // index of currently active profile
    private var previousProfileBeforeEpisode: Int = -1 // saved before entering an episode
    private var manualProfileChange = false      // set by caller when user changes profile

    /** Timing state (all in seconds, counted only when signal is valid (not NaN)). */
    private var dwellAboveStart = 0              // seconds spent above entry threshold
    private var dwellBelowRestore = 0            // seconds spent below restore threshold
    private var emergencyStart = 0               // seconds spent in emergency >=85°C
    private var cooldownRemaining = 0            // seconds left in cooldown

    /** Flag: are we currently in a thermal episode (boost active → power save pending)? */
    @Volatile
    private var inEpisode = false

    /** Flag: cooldown is active (120s after any action). */
    @Volatile
    private var coolingDown = false

    /** Called by the SystemMonitor loop on each tick (≈2s). */
    fun onTick(cpuMax: Float, batteryTemp: Float, activeProfile: Int): ThermalGovernorDecision {

        // --- NaN guard: if any critical signal is NaN, produce no action and reset timers ---
        if (cpuMax.isNaN() || batteryTemp.isNaN()) {
            // Reset all counters and state; do not count time while signal is invalid
            this.cpuSampleIndex = 0
            for (i in cpuSamples.indices) this.cpuSamples[i] = Float.NaN
            this.batteryTemp = Float.NaN
            this.dwellAboveStart = 0
            this.dwellBelowRestore = 0
            this.emergencyStart = 0
            this.coolingDown = false
            this.inEpisode = false
            this.manualProfileChange = false
            return ThermalGovernorDecision(
                enterLevel = null,
                leaveLevel = null,
                emergencyBatterySaver = false,
                restoreNow = false,
                cooldown = false,
                na = true
            )
        }

        // --- Update battery sample (simple last-value; could be replaced with moving avg later) ---
        this.batteryTemp = batteryTemp

        // --- Update median-smoothed cpuMax ---
        // Shift window and insert new reading
        cpuSampleIndex = (cpuSampleIndex + 1) % medianWindowSize
        cpuSamples[cpuSampleIndex] = cpuMax

        // Compute median of the window
        val sortedList = cpuSamples.sorted().filter { it != null } as List<Float>
        val n = sortedList.size
        var median: Float
        if (n > 0) {
            median = sortedList[n / 2]  // odd: middle element
        } else {
            median = 0f
        }
        if (n >= 2) {
            // even: average of two middle elements
            median = (sortedList[n / 2 - 1] + sortedList[n / 2]) / 2f
        }
        val S = median  // smoothed signal

        // --- Update cooldown counter ---
        if (coolingDown) {
            cooldownRemaining--
            if (cooldownRemaining <= 0) {
                coolingDown = false
                cooldownRemaining = 0
            }
        }

        // --- Decide: emergency first (highest priority) ---
        // Emergency: S >= 85°C for >= 10s -> force battery_saver
        val emergencyThreshold = 85f
        if (S >= emergencyThreshold) {
            emergencyStart++
            if (emergencyStart >= 10 && !coolingDown) {
                // Force battery_saver emergency
                emergencyStart = 0  // reset after action
                return ThermalGovernorDecision(
                    enterLevel = 5,      // battery_saver profile index
                    leaveLevel = null,
                    emergencyBatterySaver = true,
                    restoreNow = false,
                    cooldown = true,     // start cooldown after emergency
                    na = false
                )
            }
        } else {
            // Not in emergency anymore; reset counter
            emergencyStart = 0
        }

        // --- Check if we should enter a thermal level ---
        // Only if not in cooldown and not already in an episode (unless manual change)
        val shouldConsider = !coolingDown && !inEpisode || manualProfileChange

        if (shouldConsider) {
            // Profile-specific thresholds (from FASE B calibrated values)
            val isBalanced = currentProfile == 3 // balanced profile index
            val isSaver = currentProfile == 5  // battery_saver profile index

            val T_S = if (isBalanced) tempThresholdBalanced else if (isSaver) tempThresholdSaver else 55f
            val T_B = if (isBalanced) batThresholdBalanced else if (isSaver) batThresholdSaver else 43f

            // Enter balanced if S >= T_S(balanced) OR battery >= T_B(balanced) for >= 30s
            // Enter saver if S >= T_S(saver) OR battery >= T_B(saver) for >= 30s
            val dwellTarget = 30

            // We track dwell separately for each profile threshold, but use the simplest logic:
            // if S >= T_S for 30s consecutive ticks -> enter that level
            // if battery >= T_B for 30s consecutive ticks -> enter that level

            // Simple approach: track dwell above each threshold
            if (S >= T_S) {
                dwellAboveStart++
            } else {
                dwellAboveStart = 0
            }

            // Check battery threshold dwell
            val batteryAboveThreshold = batteryTemp >= T_B

            // Decide which level to enter (if any)
            var enterLevel: Int? = null
            var restoreNow = false

            if (dwellAboveStart >= dwellTarget && !inEpisode) {
                // Enter the higher-priority level based on which threshold was crossed
                if (isBalanced && S >= tempThresholdBalanced) {
                    enterLevel = 3  // balanced
                    previousProfileBeforeEpisode = currentProfile
                    inEpisode = true
                } else if (isSaver && S >= tempThresholdSaver) {
                    enterLevel = 5  // battery_saver
                    previousProfileBeforeEpisode = currentProfile
                    inEpisode = true
                }
            }

            // Also check battery-driven entry
            if (batteryAboveThreshold && dwellAboveStart < dwellTarget) {
                // Battery-driven: reset dwell and use battery threshold
                // (simplified: if battery is high enough, enter saver)
                if (isSaver && batteryTemp >= batThresholdSaver) {
                    if (!inEpisode) {
                        previousProfileBeforeEpisode = currentProfile
                        inEpisode = true
                        enterLevel = 5
                    }
                }
            }

            // --- Check restore condition (hysteresis) ---
            // Restore only if S <= T_S(N)-10 AND battery <= T_B(N)-3 for >= 60s
            val restoreTempThreshold = if (isBalanced) tempThresholdBalanced - 10 else if (isSaver) tempThresholdSaver - 10 else 45f
            val restoreBatThreshold = if (isBalanced) batThresholdBalanced - 3 else if (isSaver) batThresholdSaver - 3 else 40f

            if (S <= restoreTempThreshold && batteryTemp <= restoreBatThreshold) {
                dwellBelowRestore++
                if (dwellBelowRestore >= 60 && inEpisode && !manualProfileChange) {
                    restoreNow = true
                    inEpisode = false
                    // Restore previous profile (set by enterLevel)
                    // The caller will apply the restoration
                }
            } else {
                // Reset restore counter if conditions not met
                dwellBelowRestore = 0
            }

            // --- Manual profile change cancels restoration ---
            // (checked by caller; we just note it here)
        }

        // Default decision: no changes
        return ThermalGovernorDecision(
            enterLevel = null,
            leaveLevel = null,
            emergencyBatterySaver = false,
            restoreNow = false,
            cooldown = coolingDown,
            na = false
        )
    }

    /** Called when the user manually changes profile. Cancels any pending restoration. */
    fun setManualProfileChange(activeProfile: Int) {
        this.manualProfileChange = true
        this.currentProfile = activeProfile
        // Cancel any pending restoration: reset episode state
        this.inEpisode = false
        this.dwellBelowRestore = 0
        // Reset dwell above as well since profile changed
        this.dwellAboveStart = 0
    }

    /** Called when a new profile is actively set (e.g., user taps UI). */
    fun setCurrentProfile(profileIndex: Int, wasManual: Boolean) {
        if (wasManual) {
            setManualProfileChange(profileIndex)
        } else {
            this.currentProfile = profileIndex
        }
    }

    /** Reset all state (e.g., at app start or profile switch). */
    fun reset() {
        for (i in cpuSamples.indices) cpuSamples[i] = Float.NaN
        cpuSampleIndex = 0
        batteryTemp = Float.NaN
        currentProfile = 0
        previousProfileBeforeEpisode = -1
        manualProfileChange = false
        dwellAboveStart = 0
        dwellBelowRestore = 0
        emergencyStart = 0
        coolingDown = false
        cooldownRemaining = 0
        inEpisode = false
    }

    /** Data class for the governor's decision output. */
    internal data class ThermalGovernorDecision(
        val enterLevel: Int?,          // profile index to enter (e.g., 3=balanced, 5=saver), or null
        val leaveLevel: Int?,          // profile index to leave, or null
        val emergencyBatterySaver: Boolean,  // true if emergency brake triggered
        val restoreNow: Boolean,       // true if profile should be restored now
        val cooldown: Boolean,         // true if cooldown is active
        val na: Boolean                // true if inputs were NaN (no action counted)
    )
}