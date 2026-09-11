package com.n44r.app.session

enum class Gender { MALE, FEMALE, OTHER }

data class UserProfile(
    val name: String = "",
    val gender: Gender = Gender.MALE,
    val age: Int = 40,
    val restingHr: Int? = null
)

/**
 * Heart-rate zone estimates for the Nordic 4x4 protocol.
 * HRmax: Tanaka (208 - 0.7*age) for men/other, Gulati (206 - 0.88*age) for women - both
 * are population estimates, a measured HRmax always beats them.
 * If the resting HR is known the Karvonen (heart-rate-reserve) method is used, which is
 * noticeably more individual than plain %HRmax; without it we fall back to %HRmax.
 */
object HrZones {
    fun estimateMaxHr(profile: UserProfile): Int = when (profile.gender) {
        Gender.FEMALE -> (206 - 0.88 * profile.age).toInt()
        else -> (208 - 0.7 * profile.age).toInt()
    }

    fun targetHr(profile: UserProfile, intensity: Double): Int {
        val max = estimateMaxHr(profile)
        val rest = profile.restingHr
        return if (rest != null && rest in 30..120) (rest + (max - rest) * intensity).toInt()
        else (max * intensity).toInt()
    }

    private fun zone(profile: UserProfile, low: Double, high: Double) =
        targetHr(profile, low) to targetHr(profile, high)

    /** Nordic 4x4 work interval: 85-95 % (the whole point of the protocol). */
    fun nordicWorkZone(profile: UserProfile) = zone(profile, 0.85, 0.95)
    /** Active recovery between intervals: ~60-70 %. */
    fun nordicRecoveryZone(profile: UserProfile) = zone(profile, 0.60, 0.70)
    /** Warm-up / cool-down: easy, 50-65 %. */
    fun easyZone(profile: UserProfile) = zone(profile, 0.50, 0.65)

    fun usesKarvonen(profile: UserProfile) = profile.restingHr?.let { it in 30..120 } == true
}
