package dev.duma.android.hal.plugins.sunmi.scanner.inner

import android.content.Context
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Provides beep and vibrate feedback for scan events.
 */
internal class Beeper(context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder().build()
    private val soundId: Int = soundPool.load(context, R.raw.beep, 1)
    private val vibrator: Vibrator? = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun beep() {
        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
    }

    fun vibrate() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(100)
        }
    }
}
