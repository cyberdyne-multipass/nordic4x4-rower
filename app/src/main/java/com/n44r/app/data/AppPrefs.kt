package com.n44r.app.data

import android.content.Context
import android.content.SharedPreferences
import com.n44r.app.session.*

/**
 * All persisted app state: user profile and the complete training configuration, so the
 * next launch restores exactly what was used last time (the landing screen's "Start" button
 * relies on this). Stored field-by-field in SharedPreferences - no JSON dependency needed.
 */
object AppPrefs {
    private const val PREFS = "n44r_prefs"
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- profile ----------
    fun loadProfile(c: Context): UserProfile? {
        val p = sp(c)
        if (!p.contains("profile_age")) return null
        val genderName = p.getString("profile_gender", Gender.MALE.name) ?: Gender.MALE.name
        return UserProfile(
            name = p.getString("profile_name", "") ?: "",
            gender = runCatching { Gender.valueOf(genderName) }.getOrDefault(Gender.MALE),
            age = p.getInt("profile_age", 40),
            restingHr = p.getInt("profile_resting_hr", -1).takeIf { it > 0 }
        )
    }

    fun saveProfile(c: Context, profile: UserProfile) {
        sp(c).edit()
            .putString("profile_name", profile.name)
            .putString("profile_gender", profile.gender.name)
            .putInt("profile_age", profile.age)
            .putInt("profile_resting_hr", profile.restingHr ?: -1)
            .apply()
    }

    // ---------- training settings ----------
    data class Settings(
        val mode: SessionMode,
        val mainMetric: MainMetric,
        val timeTargetSec: Int,
        val distanceTargetM: Int,
        val interval: IntervalConfig
    )

    fun loadSettings(c: Context): Settings? {
        val p = sp(c)
        if (!p.contains("s_mode")) return null
        val mode = runCatching { SessionMode.valueOf(p.getString("s_mode", "")!!) }.getOrDefault(SessionMode.INTERVAL)
        val metric = runCatching { MainMetric.valueOf(p.getString("s_metric", "")!!) }.getOrDefault(MainMetric.DISTANCE)
        val interval = IntervalConfig(
            includeWarmup = p.getBoolean("i_warmup_on", true),
            warmupSec = p.getInt("i_warmup_sec", 600),
            workSec = p.getInt("i_work_sec", 240),
            restSec = p.getInt("i_rest_sec", 180),
            rounds = p.getInt("i_rounds", 4),
            includeCooldown = p.getBoolean("i_cooldown_on", true),
            cooldownSec = p.getInt("i_cooldown_sec", 300),
            workTarget = loadTarget(p, "t_work"),
            restTarget = loadTarget(p, "t_rest")
        )
        return Settings(mode, metric, p.getInt("s_time_sec", 1200), p.getInt("s_dist_m", 5000), interval)
    }

    fun saveSettings(c: Context, s: Settings) {
        val e = sp(c).edit()
            .putString("s_mode", s.mode.name)
            .putString("s_metric", s.mainMetric.name)
            .putInt("s_time_sec", s.timeTargetSec)
            .putInt("s_dist_m", s.distanceTargetM)
            .putBoolean("i_warmup_on", s.interval.includeWarmup)
            .putInt("i_warmup_sec", s.interval.warmupSec)
            .putInt("i_work_sec", s.interval.workSec)
            .putInt("i_rest_sec", s.interval.restSec)
            .putInt("i_rounds", s.interval.rounds)
            .putBoolean("i_cooldown_on", s.interval.includeCooldown)
            .putInt("i_cooldown_sec", s.interval.cooldownSec)
        saveTarget(e, "t_work", s.interval.workTarget)
        saveTarget(e, "t_rest", s.interval.restTarget)
        e.apply()
    }

    private fun loadTarget(p: SharedPreferences, k: String): PhaseTarget {
        val type = runCatching { TargetType.valueOf(p.getString("${k}_type", "")!!) }.getOrDefault(TargetType.NONE)
        fun opt(key: String) = p.getInt(key, -1).takeIf { it > 0 }
        return PhaseTarget(
            type = type,
            paceTargetMinSec = opt("${k}_pace_min"),
            paceTargetMaxSec = opt("${k}_pace_max"),
            hrZoneLowBpm = opt("${k}_hr_low"),
            hrZoneHighBpm = opt("${k}_hr_high")
        )
    }

    private fun saveTarget(e: SharedPreferences.Editor, k: String, t: PhaseTarget) {
        e.putString("${k}_type", t.type.name)
            .putInt("${k}_pace_min", t.paceTargetMinSec ?: -1)
            .putInt("${k}_pace_max", t.paceTargetMaxSec ?: -1)
            .putInt("${k}_hr_low", t.hrZoneLowBpm ?: -1)
            .putInt("${k}_hr_high", t.hrZoneHighBpm ?: -1)
    }
}
