package org.jellyfin.mobile.player.videoproxy

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.DialogVideoProxyVolumeBinding

/** Changes take effect immediately and are retained when the dialog closes. */
fun showVideoProxyVolumeDialog(
    context: Context,
    preferences: AppPreferences,
    onVolumeChanged: (Int) -> Unit,
): AlertDialog = VideoProxyVolumeDialog(context, preferences, onVolumeChanged).show()

private class VideoProxyVolumeDialog(
    private val context: Context,
    private val preferences: AppPreferences,
    private val onVolumeChanged: (Int) -> Unit,
) {
    private val binding = DialogVideoProxyVolumeBinding.inflate(LayoutInflater.from(context))
    private val dialog = AlertDialog.Builder(context).setView(binding.root).create()
    private val normalVolume = AppPreferences.DEFAULT_VIDEO_PROXY_VOLUME
    private val maxVolume = AppPreferences.MAX_VIDEO_PROXY_VOLUME

    init {
        binding.volumeMin.text = formatVolume(0)
        binding.volumeNormal.text = formatVolume(normalVolume)
        binding.volumeMax.text = formatVolume(maxVolume)
        binding.done.setOnClickListener { dialog.dismiss() }
        binding.volumeSlider.apply {
            max = maxVolume
            progress = preferences.videoProxyVolume
            renderVolume(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    onVolumeChanged(progress)
                    preferences.videoProxyVolume = progress
                    renderVolume(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
    }

    fun show(): AlertDialog {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val resources = context.resources
            val margin = resources.getDimensionPixelSize(R.dimen.video_proxy_volume_dialog_padding)
            val width = resources.getDimensionPixelSize(R.dimen.video_proxy_volume_dialog_width)
                .coerceAtMost(resources.displayMetrics.widthPixels - margin * 2)
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun renderVolume(value: Int) {
        val color = ContextCompat.getColor(
            context,
            if (value > normalVolume) R.color.video_proxy_volume_boost else R.color.jellyfin_accent,
        )
        binding.volumeValue.text = formatVolume(value)
        binding.volumeValue.setTextColor(color)
        binding.volumeSlider.progressTintList = ColorStateList.valueOf(color)
        binding.volumeSlider.thumbTintList = ColorStateList.valueOf(color)
        binding.volumeStatus.setText(
            when {
                value == 0 -> R.string.pref_video_proxy_volume_muted
                value < normalVolume -> R.string.pref_video_proxy_volume_reduced
                value == normalVolume -> R.string.pref_video_proxy_volume_normal
                else -> R.string.pref_video_proxy_volume_boost
            },
        )
    }

    private fun formatVolume(value: Int) = context.getString(R.string.pref_video_proxy_volume_value, value)
}
