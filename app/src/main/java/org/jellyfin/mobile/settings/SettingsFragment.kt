package org.jellyfin.mobile.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.annotation.StringRes
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewFeature
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.databinding.FragmentSettingsBinding
import org.jellyfin.mobile.downloads.DownloadMethod
import org.jellyfin.mobile.network.HostsParser
import org.jellyfin.mobile.player.videoproxy.GpuBenchmark
import org.jellyfin.mobile.player.videoproxy.NpuBenchmarkPreset
import org.jellyfin.mobile.player.videoproxy.NpuBenchmarkTier
import org.jellyfin.mobile.player.videoproxy.NpuRuntime
import org.jellyfin.mobile.player.videoproxy.OverlayTrial
import org.jellyfin.mobile.settings.SettingsList.Option
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.isPackageInstalled
import org.koin.android.ext.android.inject
import com.google.android.material.R as MaterialR

class SettingsFragment : Fragment() {
    private val appPreferences: AppPreferences by inject()
    private val storageManager: StorageManager by inject()
    private var settingsList: SettingsList? = null
    private var storageLocationName: CharSequence? = null

    private val storageLocationPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            storageManager.changeStorageLocation(uri)
            refreshStorageLocation()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val localInflater = inflater.cloneInContext(requireContext().withSettingsTheme(arguments?.getString(WEB_SETTINGS_COLORS_ARG)))
        val binding = FragmentSettingsBinding.inflate(localInflater, container, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.root.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            binding.appBar.updatePadding(top = insets.top)
            windowInsets
        }

        binding.toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.scrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, oldY ->
            if ((y > 0) != (oldY > 0)) {
                val color = if (y > 0) MaterialR.attr.colorSurfaceContainer else MaterialR.attr.colorSurface
                binding.appBar.setBackgroundColor(MaterialColors.getColor(binding.appBar, color))
            }
        })

        settingsList = SettingsList(binding.sections, appPreferences.sharedPreferences)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkNotNull(settingsList).buildSettings()
        refreshStorageLocation()
    }

    override fun onResume() {
        super.onResume()
        refreshStorageLocation()
    }

    override fun onDestroyView() {
        settingsList?.dismissDialogs()
        settingsList = null
        super.onDestroyView()
    }

    private fun refreshStorageLocation() {
        storageLocationName = storageManager.getStorageLocation()?.name
        settingsList?.refresh()
    }

    private fun SettingsList.buildSettings() {
        section(R.string.pref_category_network)
        val proxyOverrideSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        toggle(
            Constants.PREF_CUSTOM_HOSTS_ENABLED,
            R.string.pref_custom_hosts_enabled,
            summaryRes = if (proxyOverrideSupported) R.string.pref_custom_hosts_summary else R.string.custom_hosts_webview_unsupported,
            enabled = { proxyOverrideSupported },
        )
        action(
            R.string.pref_custom_hosts_entries,
            summary = { getText(R.string.pref_custom_hosts_entries_summary) },
            onClick = { showHostsInputDialog() },
        )

        section(R.string.pref_category_music_player)
        toggle(
            Constants.PREF_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE,
            R.string.pref_music_notification_always_dismissible_title,
            summaryRes = R.string.pref_music_notification_always_dismissible_summary_off,
            summaryOnRes = R.string.pref_music_notification_always_dismissible_summary_on,
        )

        section(R.string.pref_category_video_player)
        choice(
            R.string.pref_video_player_type_title,
            options = listOf(
                Option(VideoPlayerType.WEB_PLAYER, R.string.video_player_web, R.string.video_player_web_description),
                Option(VideoPlayerType.EXO_PLAYER, R.string.video_player_integrated, R.string.video_player_native_description),
                Option(VideoPlayerType.EXTERNAL_PLAYER, R.string.video_player_external, R.string.video_player_external_description),
            ),
            selected = { appPreferences.videoPlayerType },
            onSelect = { appPreferences.videoPlayerType = it },
        )
        addIntegratedPlayerSettings()
        addVideoProxySettings()
        addExternalPlayerSetting()

        section(R.string.pref_category_downloads)
        choice(
            R.string.network_title,
            options = listOf(
                Option(DownloadMethod.WIFI_ONLY, R.string.wifi_only, R.string.wifi_only_summary),
                Option(DownloadMethod.MOBILE_DATA, R.string.mobile_data, R.string.mobile_data_summary),
                Option(DownloadMethod.MOBILE_AND_ROAMING, R.string.mobile_data_and_roaming, R.string.mobile_data_and_roaming_summary),
            ),
            selected = { appPreferences.downloadMethod },
            onSelect = { appPreferences.downloadMethod = it },
        )
        action(R.string.pref_download_location, summary = { storageLocationName ?: getText(R.string.menu_item_none) }) {
            val currentUri = storageManager.getStorageLocation()?.uri
            storageLocationPicker.launch(currentUri ?: storageManager.defaultStorageLocation)
        }

        if (BuildConfig.DEBUG) {
            section(R.string.pref_category_developer)
            toggle(
                Constants.PREF_END_PROCESS_ON_EXIT,
                R.string.pref_end_process_on_exit_title,
                summaryRes = R.string.pref_end_process_on_exit_summary,
                default = true,
            )
            toggle(
                Constants.PREF_CLEAR_CACHE_ON_STARTUP,
                R.string.pref_clear_cache_on_startup_title,
                summaryRes = R.string.pref_clear_cache_on_startup_summary,
                default = true,
            )
        }
    }

    private fun SettingsList.addIntegratedPlayerSettings() {
        val integratedPlayerSelected = { appPreferences.videoPlayerType == VideoPlayerType.EXO_PLAYER }
        toggle(
            Constants.PREF_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE,
            R.string.pref_exoplayer_start_landscape_video_in_landscape,
            visible = integratedPlayerSelected,
        )
        toggle(
            Constants.PREF_EXOPLAYER_ALLOW_SWIPE_GESTURES,
            R.string.pref_exoplayer_allow_brightness_volume_gesture,
            default = true,
            visible = integratedPlayerSelected,
        )
        toggle(
            Constants.PREF_EXOPLAYER_REMEMBER_BRIGHTNESS,
            R.string.pref_exoplayer_remember_brightness,
            visible = { integratedPlayerSelected() && appPreferences.exoPlayerAllowSwipeGestures },
            onChange = { if (!it) appPreferences.exoPlayerBrightness = BRIGHTNESS_OVERRIDE_NONE },
        )
        toggle(
            Constants.PREF_EXOPLAYER_ALLOW_PRESS_SPEED_UP,
            R.string.pref_exoplayer_allow_press_speed_up,
            summaryRes = R.string.pref_exoplayer_allow_press_speed_up_summary,
            default = true,
            visible = integratedPlayerSelected,
        )
        toggle(
            Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO,
            R.string.pref_exoplayer_allow_background_audio,
            summaryRes = R.string.pref_exoplayer_allow_background_audio_summary,
            visible = integratedPlayerSelected,
        )
        toggle(
            Constants.PREF_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE,
            R.string.pref_exoplayer_allow_horizontal_gesture,
            summaryRes = R.string.pref_exoplayer_allow_horizontal_gesture_summary,
            default = true,
            visible = integratedPlayerSelected,
        )
        toggle(
            Constants.PREF_EXOPLAYER_DIRECT_PLAY_ASS,
            R.string.pref_exoplayer_direct_play_ass,
            summaryRes = R.string.pref_exoplayer_direct_play_ass_summary,
            visible = integratedPlayerSelected,
        )
        stringChoice(
            Constants.PREF_EXOPLAYER_NETWORK_BUFFER,
            R.string.pref_exoplayer_network_buffer,
            options = listOf(
                Option(Constants.NETWORK_BUFFER_AUTO, R.string.network_buffer_auto, R.string.network_buffer_auto_description),
                Option(Constants.NETWORK_BUFFER_LARGE, R.string.network_buffer_large, R.string.network_buffer_large_description),
                Option(
                    Constants.NETWORK_BUFFER_EXTRA_LARGE,
                    R.string.network_buffer_extra_large,
                    R.string.network_buffer_extra_large_description,
                ),
            ),
            default = Constants.NETWORK_BUFFER_AUTO,
            visible = integratedPlayerSelected,
        )
        val subtitleSettingsIntent = Intent(Settings.ACTION_CAPTIONING_SETTINGS)
        if (subtitleSettingsIntent.resolveActivity(requireContext().packageManager) != null) {
            action(
                R.string.pref_subtitle_style,
                summary = { getText(R.string.pref_subtitle_style_summary) },
                visible = integratedPlayerSelected,
            ) { startActivity(subtitleSettingsIntent) }
        }
    }

    @Suppress("LongMethod", "MagicNumber")
    private fun SettingsList.addVideoProxySettings() {
        val webPlayerSelected = { appPreferences.videoPlayerType == VideoPlayerType.WEB_PLAYER }
        val videoProxyEnabled = { webPlayerSelected() && appPreferences.videoProxyEnabled }
        toggle(
            Constants.PREF_VIDEO_PROXY_ENABLED,
            R.string.pref_video_proxy_enabled_title,
            summaryRes = R.string.pref_video_proxy_enabled_summary,
            visible = webPlayerSelected,
        )
        toggle(
            Constants.PREF_VIDEO_PROXY_HARDWARE_DECODING,
            R.string.pref_video_proxy_hardware_decoding_title,
            summaryRes = R.string.pref_video_proxy_hardware_decoding_summary,
            default = true,
            visible = videoProxyEnabled,
        )
        addEnhancementSettings(videoProxyEnabled)

        slider(
            Constants.PREF_VIDEO_PROXY_VOLUME,
            R.string.pref_video_proxy_volume_title,
            range = 0..AppPreferences.MAX_VIDEO_PROXY_VOLUME,
            default = AppPreferences.DEFAULT_VIDEO_PROXY_VOLUME,
            unit = "%",
            summaryRes = R.string.pref_video_proxy_volume_summary,
            updateWhileDragging = true,
            visible = videoProxyEnabled,
        )
        slider(
            Constants.PREF_SUBTITLE_OFFSET,
            R.string.pref_subtitle_offset_title,
            range = 0..200,
            default = AppPreferences.DEFAULT_SUBTITLE_OFFSET,
            unit = "dp",
            step = 2,
            visible = videoProxyEnabled,
        )
        stringChoice(
            Constants.PREF_SUBTITLE_FONT,
            R.string.pref_subtitle_font_title,
            options = listOf(
                Option("default", R.string.pref_subtitle_font_default),
                Option("sans_serif", R.string.pref_subtitle_font_sans_serif),
                Option("serif", R.string.pref_subtitle_font_serif),
                Option("monospace", R.string.pref_subtitle_font_monospace),
            ),
            default = "default",
            visible = videoProxyEnabled,
        )
        slider(
            Constants.PREF_SUBTITLE_TEXT_SIZE,
            R.string.pref_subtitle_text_size_title,
            range = 8..50,
            default = AppPreferences.DEFAULT_SUBTITLE_TEXT_SIZE,
            unit = "sp",
            visible = videoProxyEnabled,
        )
        stringChoice(
            Constants.PREF_SUBTITLE_BACKGROUND,
            R.string.pref_subtitle_background_title,
            options = listOf(
                Option("transparent", R.string.pref_subtitle_background_transparent),
                Option("semi", R.string.pref_subtitle_background_semi),
                Option("opaque", R.string.pref_subtitle_background_opaque),
            ),
            default = "transparent",
            visible = videoProxyEnabled,
        )
    }

    private fun SettingsList.addEnhancementSettings(videoProxyEnabled: () -> Boolean) {
        val npuAvailable by lazy { NpuRuntime.available }
        val enhancementSettingsVisible = { videoProxyEnabled() && OverlayTrial.available }
        val npuSettingsVisible = { enhancementSettingsVisible() && npuAvailable }
        toggle(
            Constants.PREF_NPU_ENHANCEMENT,
            R.string.pref_npu_enhancement_title,
            summaryRes = R.string.pref_npu_enhancement_summary,
            visible = enhancementSettingsVisible,
            enabled = { npuAvailable },
            summaryDisabledRes = R.string.pref_npu_enhancement_unavailable,
        )
        toggle(
            Constants.PREF_NPU_IGNORE_FRAME_BUDGET,
            R.string.pref_npu_ignore_budget_title,
            summaryRes = R.string.pref_npu_ignore_budget_summary,
            visible = { npuSettingsVisible() && appPreferences.npuEnhancementEnabled },
        )

        val deviceSummary by lazy { NpuRuntime.summary(requireContext()) }
        var runningTest: Int? = null
        var benchmarkCancelling = false
        var model = "anime4k/Upscale/CNNx2S"
        var preset = NpuBenchmarkPreset("A", NpuBenchmarkTier.FAST)
        var outputChoice = 0
        var size = Size(960, 540)
        var fps = 24f
        val sizes = listOf(Size(128, 72), Size(640, 360), Size(960, 540), Size(1280, 720), Size(1920, 1080))
        val frameRates = listOf(23.976f, 24f, 25f, 30f, 50f, 60f)
        fun resolutionLabel(value: Size) = "${value.width}×${value.height}"
        fun addBenchmark(@StringRes title: Int, isPreset: Boolean, gpu: Boolean = false) = action(
            title,
            summary = {
                when {
                    runningTest == title && benchmarkCancelling -> getText(R.string.npu_bench_cancelling)
                    runningTest == title -> getText(R.string.npu_bench_running)
                    gpu -> getText(if (isPreset) R.string.pref_gpu_bench_preset_summary else R.string.pref_gpu_bench_single_summary)
                    isPreset -> getText(R.string.pref_npu_bench_preset_summary)
                    else -> deviceSummary
                }
            },
            visible = { if (gpu) videoProxyEnabled() && GpuBenchmark.available else npuSettingsVisible() },
            enabled = { runningTest == null },
        ) {
            val context = requireContext().applicationContext
            val models = when {
                isPreset -> emptyList()
                gpu -> GpuBenchmark.modelIds(context)
                else -> NpuRuntime.modelIds(context)
            }
            val modes = if (isPreset) GpuBenchmark.presets(context).map { it.mode }.distinct() else emptyList()
            if (models.isEmpty() && modes.isEmpty()) return@action
            val dialog = newDialog(title)
            val dialogContext = dialog.context
            val content = LinearLayout(dialogContext).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPaddingRelative(padding, padding / 2, padding, 0)
            }
            fun picker(@StringRes label: Int, items: List<String>, selection: Int): Spinner {
                val spinner = Spinner(dialogContext, Spinner.MODE_DROPDOWN).apply {
                    id = View.generateViewId()
                    adapter = ArrayAdapter(dialogContext, android.R.layout.simple_spinner_dropdown_item, items)
                    setSelection(selection.coerceAtLeast(0))
                }
                content.addView(TextView(dialogContext).apply {
                    setText(label)
                    setLabelFor(spinner.id)
                })
                content.addView(spinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return spinner
            }
            val targetPicker = if (isPreset) {
                picker(R.string.npu_bench_preset, modes.map { getString(R.string.npu_bench_mode_name, it) }, modes.indexOf(preset.mode))
            } else {
                picker(R.string.npu_bench_model, models, models.indexOf(model))
            }
            val tiers = NpuBenchmarkTier.entries
            val tierPicker = if (isPreset) picker(R.string.npu_bench_tier, tiers.map { getString(it.label) }, preset.tier.ordinal) else null
            val sizePicker = picker(R.string.npu_bench_resolution, sizes.map(::resolutionLabel), sizes.indexOf(size))
            val outputs: List<Pair<String, (Size) -> Size?>> = listOf(
                getString(R.string.npu_bench_double_input) to { _: Size -> null },
                getString(R.string.npu_bench_same_input) to { input: Size -> input },
            ) + listOf(Size(1280, 720), Size(1920, 1080), Size(2560, 1440), Size(3840, 2160)).map { output ->
                resolutionLabel(output) to { _: Size -> output }
            }
            val outputPicker = if (isPreset) picker(R.string.npu_bench_output_resolution, outputs.map { it.first }, outputChoice) else null
            val fpsPicker = picker(R.string.npu_bench_fps, frameRates.map { it.toString().removeSuffix(".0") }, frameRates.indexOf(fps))
            showDialog(dialog.setView(scrollable(content))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.npu_bench_run) { _, _ ->
                    val selectedIndex = targetPicker.selectedItemPosition
                    if (tierPicker != null) {
                        preset = NpuBenchmarkPreset(modes[selectedIndex], tiers[tierPicker.selectedItemPosition])
                    } else {
                        model = models[selectedIndex]
                    }
                    size = sizes[sizePicker.selectedItemPosition]
                    fps = frameRates[fpsPicker.selectedItemPosition]
                    val presetOutput = outputPicker?.let {
                        outputChoice = it.selectedItemPosition
                        outputs[outputChoice].second(size)
                    }
                    runningTest = title
                    refresh()
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val report = runWithNpuProgress(
                                newDialog(title),
                                show = ::showDialog,
                                onCancel = {
                                    benchmarkCancelling = true
                                    refresh()
                                },
                                initialPhase = if (gpu) R.string.gpu_bench_preparing else R.string.npu_bench_compiling,
                            ) { onProgress ->
                                val benchmark = if (gpu) GpuBenchmark::benchmark else NpuRuntime::benchmark
                                benchmark(context, model, size.width, size.height, 3, fps, preset.takeIf { isPreset },
                                    presetOutput, onProgress)
                            }
                            showDialog(
                                newDialog(title)
                                    .setMessage(report)
                                    .setPositiveButton(android.R.string.ok, null),
                            )
                        } finally {
                            runningTest = null
                            benchmarkCancelling = false
                            if (settingsList === this@addEnhancementSettings) refresh()
                        }
                    }
                }
            )
        }
        addBenchmark(R.string.pref_npu_bench_single_title, isPreset = false)
        addBenchmark(R.string.pref_npu_bench_preset_title, isPreset = true)
        addBenchmark(R.string.pref_gpu_bench_single_title, isPreset = false, gpu = true)
        addBenchmark(R.string.pref_gpu_bench_preset_title, isPreset = true, gpu = true)
    }

    private fun SettingsList.addExternalPlayerSetting() {
        val packageManager = requireContext().packageManager
        val systemDefault = ExternalPlayerPackage.SYSTEM_DEFAULT
        val options = listOf(
            Option(systemDefault, R.string.external_player_system_default, R.string.external_player_system_default_description),
            Option(ExternalPlayerPackage.MPV_PLAYER, R.string.external_player_mpv, R.string.external_player_mpv_description),
            Option(
                ExternalPlayerPackage.MX_PLAYER_FREE,
                R.string.external_player_mx_player_free,
                R.string.external_player_mx_player_free_description,
            ),
            Option(
                ExternalPlayerPackage.MX_PLAYER_PRO,
                R.string.external_player_mx_player_pro,
                R.string.external_player_mx_player_pro_description,
            ),
            Option(
                ExternalPlayerPackage.VLC_PLAYER,
                R.string.external_player_vlc_player,
                R.string.external_player_vlc_player_description,
            ),
            Option(
                ExternalPlayerPackage.MPVKT_PLAYER,
                R.string.external_player_mpvkt,
                R.string.external_player_mpvkt_description,
            ),
        ).filter { it.value == systemDefault || packageManager.isPackageInstalled(it.value) }
        if (options.none { it.value == appPreferences.externalPlayerApp }) {
            appPreferences.externalPlayerApp = systemDefault
        }
        choice(
            R.string.external_player_app,
            options,
            selected = { appPreferences.externalPlayerApp },
            visible = { appPreferences.videoPlayerType == VideoPlayerType.EXTERNAL_PLAYER },
            onSelect = { appPreferences.externalPlayerApp = it },
        )
    }

    @Suppress("MagicNumber")
    private fun SettingsList.showHostsInputDialog() {
        val binding = input()
        binding.message.setText(R.string.custom_hosts_format)
        binding.message.isVisible = true
        binding.inputLayout.hint = "192.168.1.10 jellyfin.example.com"
        binding.input.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            maxLines = 12
            setText(appPreferences.customHostsEntries)
        }
        val dialog = showDialog(
            newDialog(R.string.pref_custom_hosts_entries)
                .setView(scrollable(binding.root))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null),
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = binding.input.text.toString()
            try {
                HostsParser.parse(text)
                appPreferences.customHostsEntries = text
                dialog.dismiss()
            } catch (exception: IllegalArgumentException) {
                binding.inputLayout.error = getString(R.string.custom_hosts_invalid_line, exception.message)
            }
        }
    }
}
