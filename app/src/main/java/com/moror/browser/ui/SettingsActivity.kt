package com.moror.browser.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.moror.browser.R
import com.moror.browser.data.MiniClipPreferences
import com.moror.browser.data.ProfileManager
import com.moror.browser.databinding.ActivitySettingsBinding
import com.moror.browser.engine.FastCacheEngine
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var soundPool: SoundPool? = null
    private val soundMap = mutableMapOf<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSampleAudio()

        binding.btnBackSettings.setOnClickListener {
            finish()
            overridePendingTransition(0, 0)
        }

        binding.rowClearAllData.setOnClickListener {
            showClearAllConfirmation()
        }

        binding.rowSearchEngine.setOnClickListener {
            showSearchEngineChooser()
        }

        setupMiniClipSettings()
    }

    private fun setupSampleAudio() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(audioAttributes)
                .build()
            soundMap[MiniClipPreferences.SOUND_TYPE_CAMERA] = soundPool?.load(this, R.raw.sound_camera, 1) ?: 0
            soundMap[MiniClipPreferences.SOUND_TYPE_TICK] = soundPool?.load(this, R.raw.sound_tick, 1) ?: 0
            soundMap[MiniClipPreferences.SOUND_TYPE_WATER] = soundPool?.load(this, R.raw.sound_water, 1) ?: 0
            soundMap[MiniClipPreferences.SOUND_TYPE_SENT] = soundPool?.load(this, R.raw.sound_sent, 1) ?: 0
        } catch (e: Exception) {}
    }

    private fun playSampleSound(type: String? = null, volumePercent: Int? = null) {
        try {
            val currentType = type ?: MiniClipPreferences.getImportSoundType(this)
            val vol = (volumePercent ?: MiniClipPreferences.getImportSoundVolume(this)) / 100f
            val soundId = soundMap[currentType] ?: soundMap[MiniClipPreferences.SOUND_TYPE_CAMERA] ?: 0
            if (soundPool != null && soundId != 0) {
                soundPool?.play(soundId, vol, vol, 1, 0, 1f)
            }
        } catch (e: Exception) {}
    }

    private fun playSampleVibration(durationMs: Int? = null) {
        try {
            val duration = (durationMs ?: MiniClipPreferences.getImportVibrationDuration(this)).toLong().coerceAtLeast(20L)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE),
                        audioAttributes
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
        } catch (e: Exception) {}
    }

    private fun getSoundTypeDisplayName(type: String): String {
        return when (type) {
            MiniClipPreferences.SOUND_TYPE_CAMERA -> "شاتر دوربین (پیش‌فرض)"
            MiniClipPreferences.SOUND_TYPE_TICK -> "تیک ساعت / مکانیکی"
            MiniClipPreferences.SOUND_TYPE_WATER -> "قطره آب ملایم"
            MiniClipPreferences.SOUND_TYPE_SENT -> "زنگوله ارسال پیام"
            else -> "شاتر دوربین"
        }
    }

    private fun getVibrationIntensityLabel(durationMs: Int): String {
        val levelName = when {
            durationMs <= 35 -> "بسیار خفیف"
            durationMs <= 55 -> "ملایم"
            durationMs <= 85 -> "استاندارد (نرمال)"
            else -> "قوی"
        }
        return "$levelName ($durationMs میلی‌ثانیه)"
    }

    override fun onDestroy() {
        try {
            soundPool?.release()
            soundPool = null
            soundMap.clear()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshMiniClipUI()
    }

    private var isUpdatingUI = false

    private fun refreshMiniClipUI() {
        isUpdatingUI = true
        try {
            val dir = MiniClipPreferences.getSaveDirectory(this)
            binding.tvCurrentSaveDirectory.text = dir.name

            val isExact = MiniClipPreferences.isExactDimensionMatch(this)
            val w = MiniClipPreferences.getTargetWidth(this)
            val h = MiniClipPreferences.getTargetHeight(this)
            binding.tvCurrentMinDimension.text = if (isExact) {
                "🔒 قفل دقیق: $w × $h پیکسل (نه بزرگ‌تر و نه کوچک‌تر)"
            } else {
                "🔍 فقط بزرگ‌تر یا مساوی: $w × $h پیکسل"
            }

            val isLocked = MiniClipPreferences.isNegativePromptLocked(this)
            if (isLocked) {
                val lockedText = MiniClipPreferences.getLockedNegativePrompt(this)
                val snippet = if (lockedText.length > 25) lockedText.take(25) + "..." else lockedText
                binding.tvNegativeLockStatus.text = "🔒 قفل است: \"$snippet\" (لمس برای ریست)"
                binding.tvNegativeLockStatus.setTextColor(getColor(com.moror.browser.R.color.accent_green))
            } else {
                binding.tvNegativeLockStatus.text = "🔓 قفل نیست (متن برای هر بار قابل تغییر است)"
                binding.tvNegativeLockStatus.setTextColor(getColor(com.moror.browser.R.color.text_secondary))
            }

            val cacheSizeBytes = FastCacheEngine.getCacheSizeBytes(this)
            val formatted = FastCacheEngine.formatFileSize(cacheSizeBytes)
            binding.tvFastCacheSize.text = "حجم ذخیره‌شده: $formatted (لمس برای مشاهده نمودار و مدیریت)"

            val isBlockVideos = MiniClipPreferences.isBlockVideosEnabled(this)
            binding.switchBlockVideos.isChecked = isBlockVideos
            updateBlockVideosDescription(isBlockVideos)

            val isBurstProtection = MiniClipPreferences.isBurstProtectionEnabled(this)
            binding.switchBurstProtection.isChecked = isBurstProtection
            updateBurstProtectionDescription(isBurstProtection)

            val isManualClipboardOnly = MiniClipPreferences.isManualClipboardOnly(this)
            binding.switchManualClipboardOnly.isChecked = isManualClipboardOnly
            updateManualClipboardOnlyDescription(isManualClipboardOnly)

            val isSoundEnabled = MiniClipPreferences.isImportSoundEnabled(this)
            binding.switchImportSound.isChecked = isSoundEnabled
            binding.layoutSoundControls.visibility = if (isSoundEnabled) View.VISIBLE else View.GONE
            updateImportSoundDescription(isSoundEnabled)

            val soundType = MiniClipPreferences.getImportSoundType(this)
            binding.tvCurrentSoundType.text = getSoundTypeDisplayName(soundType)

            val soundVol = MiniClipPreferences.getImportSoundVolume(this)
            binding.sbSoundVolume.progress = soundVol
            binding.tvSoundVolumePercent.text = "${soundVol}٪"

            val isVibrationEnabled = MiniClipPreferences.isImportVibrationEnabled(this)
            binding.switchImportVibration.isChecked = isVibrationEnabled
            binding.layoutVibrationControls.visibility = if (isVibrationEnabled) View.VISIBLE else View.GONE
            updateImportVibrationDescription(isVibrationEnabled)

            val vibDuration = MiniClipPreferences.getImportVibrationDuration(this)
            binding.sbVibrationIntensity.progress = vibDuration
            binding.tvVibrationIntensityLabel.text = getVibrationIntensityLabel(vibDuration)

            val isAutoMix = MiniClipPreferences.isAutoMixClipboardEnabled(this)
            binding.switchAutoMixClipboard.isChecked = isAutoMix
            updateAutoMixClipboardDescription(isAutoMix)

            val isTabsBarEnabled = MiniClipPreferences.isWebTabsBarEnabled(this)
            binding.switchShowWebTabsBar.isChecked = isTabsBarEnabled
            updateShowWebTabsBarDescription(isTabsBarEnabled)

            val searchEngine = MiniClipPreferences.getSearchEngine(this)
            binding.tvCurrentSearchEngine.text = searchEngine
        } finally {
            isUpdatingUI = false
        }
    }

    private fun updateBlockVideosDescription(isBlockVideos: Boolean) {
        binding.tvBlockVideosDesc.text = if (isBlockVideos) {
            "مسدودسازی فعال است (ویدیوها لود و ذخیره نمی‌شوند و دیتایی مصرف نمی‌کنند)"
        } else {
            "غیرفعال است (ویدیوها طبق روال عادی لود می‌شوند)"
        }
        binding.tvBlockVideosDesc.setTextColor(
            if (isBlockVideos) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
    }

    private fun updateBurstProtectionDescription(enabled: Boolean) {
        binding.tvBurstProtectionDesc.text = if (enabled) {
            "فعال است (در صورت ایمپورت ناگهانی بیش از ۴ عکس هنگام تعویض مدل، در ۳ ثانیه خودکار پاکسازی می‌شود)"
        } else {
            "غیرفعال است (تمام تصاویر بدون ریست خودکار وارد می‌شوند)"
        }
        binding.tvBurstProtectionDesc.setTextColor(
            if (enabled) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
    }

    private fun updateManualClipboardOnlyDescription(enabled: Boolean) {
        binding.tvManualClipboardOnlyDesc.text = if (enabled) {
            "فعال است (مینی‌کلیپ متن را خودکار نمی‌خواند؛ درج پرامپت فقط با دکمه الصاق دستی)"
        } else {
            "غیرفعال است (متن کلیپ‌بورد به‌صورت خودکار وارد کادر مینی‌کلیپ می‌شود)"
        }
        binding.tvManualClipboardOnlyDesc.setTextColor(
            if (enabled) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
    }

    private fun updateImportSoundDescription(enabled: Boolean) {
        binding.tvImportSoundDesc.text = if (enabled) {
            "فعال است (هنگام ایمپورت صدای کلیک پخش می‌شود؛ تا ۵ ثانیه بعد برای عکس‌های متوالی بی‌صدا است)"
        } else {
            "غیرفعال است (ایمپورت عکس بدون صدا انجام می‌شود)"
        }
        binding.tvImportSoundDesc.setTextColor(
            if (enabled) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
    }

    private fun updateImportVibrationDescription(enabled: Boolean) {
        binding.tvImportVibrationDesc.text = if (enabled) {
            "فعال است (لرزش محسوس؛ تا ۵ ثانیه بعد برای عکس‌های متوالی بی‌لرزش است)"
        } else {
            "غیرفعال است (ایمپورت عکس بدون لرزش انجام می‌شود)"
        }
        binding.tvImportVibrationDesc.setTextColor(
            if (enabled) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
    }

    private fun updateAutoMixClipboardDescription(enabled: Boolean) {
        binding.tvAutoMixClipboardDesc.text = if (enabled) {
            "فعال است (متن کلیپ‌بورد خودکار با میکسر ترکیب و برچسب زرد میکس شده نمایش می‌یابد)"
        } else {
            "غیرفعال است (ترکیب خودکار انجام نمی‌شود)"
        }
        binding.tvAutoMixClipboardDesc.setTextColor(
            if (enabled) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
    }

    private fun updateShowWebTabsBarDescription(enabled: Boolean) {
        binding.tvShowWebTabsBarDesc.text = if (enabled) {
            "فعال است (نوار تب‌ها در بالای صفحه نمایش داده می‌شود)"
        } else {
            "غیرفعال است (نوار تب‌ها کاملاً مخفی است و فضای مرورگر بزرگتر می‌شود)"
        }
        binding.tvShowWebTabsBarDesc.setTextColor(
            if (enabled) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
    }

    private fun setupMiniClipSettings() {
        refreshMiniClipUI()

        binding.rowSaveDirectory.setOnClickListener {
            showCustomDirectoryDialog {
                refreshMiniClipUI()
            }
        }

        binding.rowMinDimension.setOnClickListener {
            showCustomDimensionInputDialog {
                refreshMiniClipUI()
            }
        }

        binding.rowFastCache.setOnClickListener {
            val intent = Intent(this, CacheManagerActivity::class.java)
            startActivity(intent)
            overridePendingTransition(0, 0)
        }

        binding.switchBlockVideos.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            MiniClipPreferences.setBlockVideosEnabled(this, isChecked)
            updateBlockVideosDescription(isChecked)
            val msg = if (isChecked) "بارگیری ویدیوها مسدود شد" else "بارگیری ویدیوها فعال شد"
            showTopPillNotification(msg)
        }

        binding.rowBlockVideos.setOnClickListener {
            binding.switchBlockVideos.toggle()
        }

        binding.switchBurstProtection.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            MiniClipPreferences.setBurstProtectionEnabled(this, isChecked)
            updateBurstProtectionDescription(isChecked)
            val msg = if (isChecked) "ریست خودکار تصاویر ناخواسته فعال شد" else "ریست خودکار غیرفعال شد"
            showTopPillNotification(msg)
        }

        binding.rowBurstProtection.setOnClickListener {
            binding.switchBurstProtection.toggle()
        }

        binding.switchManualClipboardOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            MiniClipPreferences.setManualClipboardOnly(this, isChecked)
            updateManualClipboardOnlyDescription(isChecked)
            val msg = if (isChecked) "الصاق دستی فعال شد (بدون خواندن خودکار)" else "خواندن خودکار کلیپ‌بورد فعال شد"
            showTopPillNotification(msg)
        }

        binding.rowManualClipboardOnly.setOnClickListener {
            binding.switchManualClipboardOnly.toggle()
        }

        binding.switchImportSound.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            MiniClipPreferences.setImportSoundEnabled(this, isChecked)
            updateImportSoundDescription(isChecked)
            binding.layoutSoundControls.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                playSampleSound()
            }
            val msg = if (isChecked) "صدای اعلان ایمپورت فعال شد" else "صدای ایمپورت غیرفعال شد"
            showTopPillNotification(msg)
        }

        binding.rowImportSound.setOnClickListener {
            binding.switchImportSound.toggle()
        }

        binding.rowImportSoundType.setOnClickListener {
            showSoundTypeChooser()
        }

        binding.sbSoundVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val vol = maxOf(10, progress)
                binding.tvSoundVolumePercent.text = "${vol}٪"
                if (fromUser) {
                    MiniClipPreferences.setImportSoundVolume(this@SettingsActivity, vol)
                    playSampleSound(volumePercent = vol)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.switchImportVibration.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            MiniClipPreferences.setImportVibrationEnabled(this, isChecked)
            updateImportVibrationDescription(isChecked)
            binding.layoutVibrationControls.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                playSampleVibration()
            }
            val msg = if (isChecked) "ویبره ایمپورت فعال شد" else "ویبره ایمپورت غیرفعال شد"
            showTopPillNotification(msg)
        }

        binding.rowImportVibration.setOnClickListener {
            binding.switchImportVibration.toggle()
        }

        binding.sbVibrationIntensity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = maxOf(20, progress)
                binding.tvVibrationIntensityLabel.text = getVibrationIntensityLabel(duration)
                if (fromUser) {
                    MiniClipPreferences.setImportVibrationDuration(this@SettingsActivity, duration)
                    playSampleVibration(duration)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.switchAutoMixClipboard.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            MiniClipPreferences.setAutoMixClipboardEnabled(this, isChecked)
            updateAutoMixClipboardDescription(isChecked)
            val msg = if (isChecked) "میکس خودکار پرامپت از کلیپ‌بورد فعال شد" else "میکس خودکار غیرفعال شد"
            showTopPillNotification(msg)
        }

        binding.rowAutoMixClipboard.setOnClickListener {
            binding.switchAutoMixClipboard.toggle()
        }

        binding.switchShowWebTabsBar.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            MiniClipPreferences.setWebTabsBarEnabled(this, isChecked)
            updateShowWebTabsBarDescription(isChecked)
            val msg = if (isChecked) "نمایش نوار تب‌ها فعال شد" else "نوار تب‌ها مخفی شد"
            showTopPillNotification(msg)
        }

        binding.rowShowWebTabsBar.setOnClickListener {
            binding.switchShowWebTabsBar.toggle()
        }

        binding.rowResetNegativePrompt.setOnClickListener {
            if (MiniClipPreferences.isNegativePromptLocked(this)) {
                AlertDialog.Builder(this)
                    .setTitle("ریست قفل پرامپت منفی")
                    .setMessage("آیا مایلید قفل پرامپت منفی باز شده و متن ذخیره شده پاک شود؟")
                    .setPositiveButton("بله، باز شود") { _, _ ->
                        MiniClipPreferences.setNegativePromptLocked(this, false)
                        MiniClipPreferences.setLockedNegativePrompt(this, "")
                        refreshMiniClipUI()
                        showTopPillNotification("قفل پرامپت منفی باز شد")
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            } else {
                showTopPillNotification("قفل پرامپت منفی هم‌اکنون باز است")
            }
        }
    }

    private fun showSoundTypeChooser() {
        val items = arrayOf(
            "شاتر دوربین (پیش‌فرض)",
            "تیک ساعت / مکانیکی",
            "قطره آب ملایم",
            "زنگوله ارسال پیام"
        )
        val keys = arrayOf(
            MiniClipPreferences.SOUND_TYPE_CAMERA,
            MiniClipPreferences.SOUND_TYPE_TICK,
            MiniClipPreferences.SOUND_TYPE_WATER,
            MiniClipPreferences.SOUND_TYPE_SENT
        )
        val currentKey = MiniClipPreferences.getImportSoundType(this)
        var selectedIndex = keys.indexOf(currentKey).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("انتخاب نوع صدای اعلان")
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
                playSampleSound(type = keys[which])
            }
            .setPositiveButton("تایید") { _, _ ->
                val chosenKey = keys[selectedIndex]
                MiniClipPreferences.setImportSoundType(this, chosenKey)
                binding.tvCurrentSoundType.text = getSoundTypeDisplayName(chosenKey)
                showTopPillNotification("نوع صدای اعلان تغییر یافت")
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showCustomDirectoryDialog(onSaved: () -> Unit) {
        val currentDir = MiniClipPreferences.getSaveDirectory(this)
        val input = EditText(this)
        input.setText(currentDir.name)

        AlertDialog.Builder(this)
            .setTitle("نام پوشه ذخیره در Downloads")
            .setMessage("فایل‌های تصویر و متن SeaArt در این پوشه در حافظه دستگاه ذخیره خواهند شد:")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    val defaultDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(defaultDownloads, folderName)
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    MiniClipPreferences.setSaveDirectory(this, targetDir.absolutePath)
                    onSaved()
                    showTopPillNotification("مسیر ذخیره به روز شد")
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showCustomDimensionInputDialog(onSaved: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_dimensions, null)
        val etWidth = dialogView.findViewById<EditText>(R.id.etCustomWidth)
        val etHeight = dialogView.findViewById<EditText>(R.id.etCustomHeight)
        val rbMinDimension = dialogView.findViewById<RadioButton>(R.id.rbMinDimension)
        val rbExactMatch = dialogView.findViewById<RadioButton>(R.id.rbExactMatch)

        val btnPresetPortrait = dialogView.findViewById<TextView>(R.id.btnPresetPortrait)
        val btnPresetLandscape = dialogView.findViewById<TextView>(R.id.btnPresetLandscape)
        val btnPresetSquare = dialogView.findViewById<TextView>(R.id.btnPresetSquare)
        val btnPreset512 = dialogView.findViewById<TextView>(R.id.btnPreset512)

        val currentW = MiniClipPreferences.getTargetWidth(this)
        val currentH = MiniClipPreferences.getTargetHeight(this)
        val currentExact = MiniClipPreferences.isExactDimensionMatch(this)

        etWidth.setText(currentW.toString())
        etHeight.setText(currentH.toString())
        if (currentExact) {
            rbExactMatch.isChecked = true
        } else {
            rbMinDimension.isChecked = true
        }

        btnPresetPortrait.setOnClickListener {
            etWidth.setText("688")
            etHeight.setText("1024")
        }

        btnPresetLandscape.setOnClickListener {
            etWidth.setText("1024")
            etHeight.setText("688")
        }

        btnPresetSquare.setOnClickListener {
            etWidth.setText("1024")
            etHeight.setText("1024")
        }

        btnPreset512.setOnClickListener {
            etWidth.setText("512")
            etHeight.setText("512")
            rbMinDimension.isChecked = true
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("ذخیره") { _, _ ->
                val w = etWidth.text.toString().trim().toIntOrNull()
                val h = etHeight.text.toString().trim().toIntOrNull()
                if (w != null && w > 0 && h != null && h > 0) {
                    val isExact = rbExactMatch.isChecked
                    MiniClipPreferences.setDimensionSettings(this, w, h, isExact)
                    onSaved()
                    val modeText = if (isExact) {
                        "قفل ابعاد دقیق: $w × $h"
                    } else {
                        "حداقل ابعاد: $w × $h پیکسل"
                    }
                    showTopPillNotification("تنظیم شد: $modeText")
                } else {
                    showTopPillNotification("لطفاً مقادیر عددی معتبر وارد کنید")
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun showClearAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("پاکسازی تمام داده‌ها")
            .setMessage("آیا مایلید حافظه پنهان و داده‌های ذخیره شده تمام پروفایل‌ها پاک شوند؟")
            .setPositiveButton("بله، پاکسازی شود") { _, _ ->
                val profiles = ProfileManager.getProfiles(this)
                for (p in profiles) {
                    ProfileManager.clearProfileDirectory(this, p.slotIndex)
                }
                showTopPillNotification("داده‌های کش تمام اکانت‌ها پاکسازی شد")
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showSearchEngineChooser() {
        val engines = arrayOf("Google (گوگل)", "DuckDuckGo", "Bing (بینگ)")
        AlertDialog.Builder(this)
            .setTitle("انتخاب موتور جستجو")
            .setItems(engines) { _, which ->
                val selected = engines[which]
                MiniClipPreferences.setSearchEngine(this, selected)
                binding.tvCurrentSearchEngine.text = selected
                showTopPillNotification("موتور جستجو روی $selected تنظیم شد")
            }
            .show()
    }

    private var notificationDismissRunnable: Runnable? = null

    fun showTopPillNotification(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val pill = binding.layoutTopNotification
            val tvText = binding.tvTopNotificationText
            val ivIcon = binding.ivTopNotificationIcon

            notificationDismissRunnable?.let { pill.removeCallbacks(it) }

            tvText.text = message
            ivIcon.setImageResource(R.drawable.ic_check_circle_green)
            ivIcon.visibility = View.VISIBLE

            pill.bringToFront()
            pill.animate().cancel()
            pill.visibility = View.VISIBLE
            pill.alpha = 0f
            val startY = -70f * resources.displayMetrics.density
            pill.translationY = startY

            pill.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(280)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
                .start()

            val dismissRunnable = Runnable {
                pill.animate()
                    .translationY(startY)
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        pill.visibility = View.GONE
                    }
                    .start()
            }
            notificationDismissRunnable = dismissRunnable
            pill.postDelayed(dismissRunnable, 1900)

            pill.setOnClickListener {
                pill.removeCallbacks(dismissRunnable)
                dismissRunnable.run()
            }
        }
    }
}
