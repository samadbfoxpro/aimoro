package com.moror.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.moror.browser.R
import com.moror.browser.data.ImagePromptSaver
import com.moror.browser.data.MiniClipPreferences
import com.moror.browser.data.ProfileManager
import com.moror.browser.data.ProfileMigrationHelper
import com.moror.browser.data.ShortcutItem
import com.moror.browser.data.ShortcutManager
import com.moror.browser.databinding.ActivityBrowserBinding
import com.moror.browser.engine.MiniClipJSBridge
import com.moror.browser.engine.WebViewHelper
import com.moror.browser.model.CapturedImage
import com.moror.browser.model.MiniClipDraft
import com.moror.browser.model.UserProfile
import com.moror.browser.ui.adapter.HomeShortcutAdapter
import com.moror.browser.ui.adapter.MiniClipImageAdapter
import com.moror.browser.ui.dialogs.ProfileManagerDialog
import com.moror.browser.ui.dialogs.PromptMixerDialog
import kotlinx.coroutines.launch

open class BaseBrowserActivity : AppCompatActivity() {

    companion object {
        var isSessionAuthenticated: Boolean = false
    }

    private var activeSlotIndex: Int = 1
    fun getSlotIndex(): Int = activeSlotIndex

    // Single-Process Multi-Profile LRU WebView Pool (max 3 WebViews in RAM)
    private val webViewPool = LinkedHashMap<Int, WebView>(3, 0.75f, true)

    // Secondary Tab for active slot (Max 2 tabs per slot: Tab 0 and Tab 1)
    private val secondaryWebViews = mutableMapOf<Int, WebView>()
    private val activeTabIndexPerSlot = mutableMapOf<Int, Int>()

    fun getActiveTabIndex(): Int = activeTabIndexPerSlot[activeSlotIndex] ?: 0

    val currentWebView: WebView
        get() {
            if (getActiveTabIndex() == 1) {
                secondaryWebViews[activeSlotIndex]?.let { return it }
            }
            return getOrCreateWebViewForSlot(activeSlotIndex)
        }

    private lateinit var binding: ActivityBrowserBinding
    private var isDesktopMode = false
    private var currentProfileId = "p_1"

    // Offline Home & Shortcuts state
    private var isHomeScreenActive = false
    private lateinit var shortcutAdapter: HomeShortcutAdapter
    private lateinit var shortcutTouchHelper: ItemTouchHelper

    // Mini-Clip state & isolated draft per account slot
    private var miniClipDraft = MiniClipDraft()
    private var miniClipAdapter: MiniClipImageAdapter? = null
    private var isNegativeLocked = false
    private var lastHandledClipboardText: String = ""
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disable window animation transitions for instant lightweight navigation
        overridePendingTransition(0, 0)

        // Step 3: Run session data migration from legacy directories if needed
        ProfileMigrationHelper.migrateIfNeeded(this)

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        if (!isSessionAuthenticated) {
            binding.root.visibility = View.INVISIBLE
        }
        setContentView(binding.root)

        val activeProfile = ProfileManager.getActiveProfile(this)
        activeSlotIndex = activeProfile.slotIndex
        currentProfileId = activeProfile.id
        ProfileManager.setActiveProfileId(this, currentProfileId)

        // Load isolated draft & state for this account slot
        miniClipDraft = MiniClipPreferences.loadSlotDraft(this, activeSlotIndex)
        lastHandledClipboardText = MiniClipPreferences.getLastHandledClipboard(this, activeSlotIndex)
        isNegativeLocked = MiniClipPreferences.isNegativePromptLocked(this, activeSlotIndex)

        setupUI()
        setupSoundPool()
        val initialWv = getOrCreateWebViewForSlot(activeSlotIndex)
        setupHomeScreen()
        setupMiniClipSidePanel()
        setupClipboardListener()
        setupBackNavigation()

        onSlotSwitchedUpdateUiAndMiniClip(activeSlotIndex, initialWv)

        // Load initial page or show Home screen
        val initialUrl = intent?.dataString ?: activeProfile.lastUsedUrl
        if (initialUrl.isNullOrBlank() || initialUrl == "https://www.google.com") {
            showHomeScreen()
        } else {
            loadUrl(initialUrl)
        }

        if (!isSessionAuthenticated) {
            checkBiometricAuth()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isSessionAuthenticated) {
            if (webViewPool.containsKey(activeSlotIndex)) {
                currentWebView.onResume()
            }
        } else {
            checkBiometricAuth()
        }
    }

    private fun checkBiometricAuth() {
        if (isSessionAuthenticated) {
            binding.root.visibility = View.VISIBLE
            return
        }

        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        // اگر دستگاه کلاً قفل یا اثر انگشتی ندارد، قفل را رد کن تا کاربر پشت در نماند
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            isSessionAuthenticated = true
            binding.root.visibility = View.VISIBLE
            return
        }

        binding.root.visibility = View.INVISIBLE

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isSessionAuthenticated = true
                    binding.root.visibility = View.VISIBLE
                    if (webViewPool.containsKey(activeSlotIndex)) {
                        currentWebView.onResume()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // اگر کاربر دکمه لغو یا Back را زد و تایید نکرد، برنامه بسته شود
                    if (!isSessionAuthenticated) {
                        finishAffinity()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // اثر انگشت اشتباه؛ دیالوگ خودش منتظر تلاش بعدی می‌ماند
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ورود به مرورگر")
            .setSubtitle("لطفاً برای دسترسی به اکانت‌ها اثر انگشت خود را وارد کنید")
            .setAllowedAuthenticators(authenticators)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            e.printStackTrace()
            isSessionAuthenticated = true
            binding.root.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        currentProfileId = "p_${getSlotIndex()}"
        ProfileManager.setActiveProfileId(this, currentProfileId)
        updateProfileBadge()

        // Sync shortcuts list (in case updated from another profile)
        if (::shortcutAdapter.isInitialized) {
            shortcutAdapter.updateList(ShortcutManager.getShortcuts(this))
        }

        // Sync latest save directory hint
        val saveDir = MiniClipPreferences.getSaveDirectory(this)
        binding.includedSidePanel.tvSidePanelSavePath.text = "مسیر ذخیره: ${saveDir.name}/[Title].webp + .txt"

        // Auto-check latest clipboard if manual mode is disabled (always overwrite with freshest copied text)
        if (!MiniClipPreferences.isManualClipboardOnly(this)) {
            checkAndReplaceClipboardIfNew()
        }

        updateBrowserTabsUI()

        // Re-inject observer with updated dimensions if on web page
        if (MiniClipPreferences.isAutoImportEnabled(this)) {
            val w = MiniClipPreferences.getTargetWidth(this)
            val h = MiniClipPreferences.getTargetHeight(this)
            val isExact = MiniClipPreferences.isExactDimensionMatch(this)
            MiniClipJSBridge.injectObserverScript(currentWebView, w, h, isExact)
        }

        // Apply video blocker script if enabled
        if (MiniClipPreferences.isBlockVideosEnabled(this)) {
            WebViewHelper.injectVideoBlockerScript(currentWebView)
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
        persistCurrentDraft()
        val currentUrl = currentWebView.url
        if (!currentUrl.isNullOrBlank()) {
            ProfileManager.updateLastUsedUrl(this, currentProfileId, currentUrl)
        }
    }

    override fun onStop() {
        if (webViewPool.containsKey(activeSlotIndex)) {
            currentWebView.onPause()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        overridePendingTransition(0, 0)
        currentProfileId = "p_${getSlotIndex()}"
        ProfileManager.setActiveProfileId(this, currentProfileId)
        updateProfileBadge()

        val newUrl = intent?.dataString
        if (!newUrl.isNullOrBlank()) {
            val currentUrl = currentWebView.url
            if (currentUrl.isNullOrBlank() || currentUrl == "https://www.google.com" || currentUrl != newUrl) {
                loadUrl(newUrl)
            }
        }
    }

    fun updateProfileBadge() {
        val profiles = ProfileManager.getProfiles(this)
        val profile = profiles.find { it.slotIndex == getSlotIndex() }
        val name = profile?.name ?: "اکانت ${getSlotIndex()}"
        binding.tvProfileBadge.text = name
    }

    private fun setupUI() {
        updateProfileBadge()

        binding.tvProfileBadge.setOnClickListener {
            openProfileManager()
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitUrl()
                true
            } else {
                false
            }
        }

        binding.etUrl.doAfterTextChanged { s ->
            binding.btnClearUrl.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
        }

        binding.btnGo.setOnClickListener {
            submitUrl()
        }

        binding.btnBack.setOnClickListener {
            if (isSidePanelOpen()) {
                closeMiniClipSidePanel()
            } else if (getActiveTabIndex() == 1) {
                val tab2Wv = secondaryWebViews[activeSlotIndex]
                if (tab2Wv != null && tab2Wv.canGoBack()) {
                    tab2Wv.goBack()
                } else {
                    closeSecondTab()
                }
            } else if (isHomeScreenActive) {
                if (currentWebView.canGoBack()) {
                    hideHomeScreen()
                }
            } else if (currentWebView.canGoBack()) {
                currentWebView.goBack()
            } else {
                showHomeScreen()
            }
        }

        // Browser Tabs Bar Listeners
        binding.tab1Chip.setOnClickListener {
            switchToTab(0)
        }

        binding.tab2Chip.setOnClickListener {
            switchToTab(1)
        }

        binding.btnTab2Close.setOnClickListener {
            closeSecondTab()
        }

        binding.btnNewWebTab.setOnClickListener {
            openSecondTab()
        }

        binding.btnForward.setOnClickListener {
            if (isHomeScreenActive) {
                hideHomeScreen()
            } else if (currentWebView.canGoForward()) {
                currentWebView.goForward()
            }
        }

        binding.btnRefresh.setOnClickListener {
            if (isHomeScreenActive) {
                val list = ShortcutManager.getShortcuts(this)
                shortcutAdapter.updateList(list)
            } else {
                currentWebView.reload()
            }
        }

        binding.btnHome.setOnClickListener {
            if (isHomeScreenActive) {
                if (!currentWebView.url.isNullOrBlank()) {
                    hideHomeScreen()
                }
            } else {
                showHomeScreen()
            }
        }

        binding.btnProfiles.setOnClickListener {
            openProfileManager()
        }

        binding.btnGallery.setOnClickListener {
            openGallery()
        }

        binding.btnMore.setOnClickListener { v ->
            showOverflowMenu(v)
        }

        // Right Edge Tab Handle click -> opens sliding Mini-Clip panel
        binding.btnMiniClipEdgeTab.setOnClickListener {
            openMiniClipSidePanel()
        }
    }

    private fun addCapturedImage(image: CapturedImage): Boolean {
        if (!image.imageUrl.isNullOrBlank() && miniClipDraft.images.any { it.imageUrl == image.imageUrl }) {
            return false
        }
        miniClipDraft.images.add(image)
        miniClipAdapter?.notifyItemInserted(miniClipDraft.images.size - 1)
        updateMiniClipImagesUI()
        animateEdgeTabPulse()
        persistCurrentDraft()
        return true
    }

    private fun updateMiniClipImagesUI() {
        val count = miniClipDraft.images.size
        binding.includedSidePanel.tvSidePanelCountBadge.text = "$count تصویر"
        if (count == 0) {
            binding.includedSidePanel.rvSidePanelImages.visibility = View.GONE
            binding.includedSidePanel.tvNoImagesPlaceholder.visibility = View.VISIBLE
            binding.tvMiniClipBadge.visibility = View.GONE
        } else {
            binding.includedSidePanel.rvSidePanelImages.visibility = View.VISIBLE
            binding.includedSidePanel.tvNoImagesPlaceholder.visibility = View.GONE
            binding.tvMiniClipBadge.visibility = View.VISIBLE
            binding.tvMiniClipBadge.text = if (count > 9) "9+" else count.toString()
        }
        updateSaveButtonState()
    }

    private fun updateSaveButtonState() {
        val hasImages = miniClipDraft.images.isNotEmpty()
        val hasPositivePrompt = binding.includedSidePanel.etSidePanelPositivePrompt.text?.toString()?.trim()?.isNotEmpty() == true
        val isSavable = hasImages || hasPositivePrompt
        binding.includedSidePanel.btnSidePanelSave.isEnabled = isSavable
    }

    private fun animateEdgeTabPulse() {
        binding.btnMiniClipEdgeTab.animate()
            .scaleX(1.22f)
            .scaleY(1.22f)
            .setDuration(130)
            .withEndAction {
                binding.btnMiniClipEdgeTab.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(160)
                    .start()
            }
            .start()
    }

    private var notificationDismissRunnable: Runnable? = null

    fun showTopPillNotification(message: String, iconRes: Int? = null) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val pill = binding.layoutTopNotification
            val tvText = binding.tvTopNotificationText
            val ivIcon = binding.ivTopNotificationIcon

            notificationDismissRunnable?.let { pill.removeCallbacks(it) }

            // Reset to default green pill styling
            pill.setBackgroundResource(R.drawable.bg_top_notification_pill)
            tvText.setTextColor(Color.WHITE)
            tvText.typeface = Typeface.DEFAULT
            ivIcon.clearColorFilter()

            tvText.text = message
            if (iconRes != null) {
                ivIcon.setImageResource(iconRes)
                ivIcon.visibility = View.VISIBLE
            } else {
                ivIcon.setImageResource(R.drawable.ic_check_circle_green)
                ivIcon.visibility = View.VISIBLE
            }

            pill.bringToFront()
            pill.animate().cancel()
            pill.visibility = View.VISIBLE
            pill.alpha = 0f
            val startY = -70f * resources.displayMetrics.density
            pill.translationY = startY

            // Smooth drop-down with subtle spring/overshoot
            pill.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(1.15f))
                .start()

            val dismissRunnable = Runnable {
                pill.animate()
                    .translationY(startY)
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(AccelerateInterpolator())
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

    fun showYellowTopPillNotification(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val pill = binding.layoutTopNotification
            val tvText = binding.tvTopNotificationText
            val ivIcon = binding.ivTopNotificationIcon

            notificationDismissRunnable?.let { pill.removeCallbacks(it) }

            // Set to distinct yellow/amber styling for mixer feedback
            pill.setBackgroundResource(R.drawable.bg_top_notification_pill_yellow)
            tvText.setTextColor(Color.parseColor("#111827"))
            tvText.typeface = Typeface.DEFAULT_BOLD
            tvText.text = message

            ivIcon.setImageResource(R.drawable.ic_shuffle)
            ivIcon.setColorFilter(Color.parseColor("#111827"))
            ivIcon.visibility = View.VISIBLE

            pill.bringToFront()
            pill.animate().cancel()
            pill.visibility = View.VISIBLE
            pill.alpha = 0f
            val startY = -70f * resources.displayMetrics.density
            pill.translationY = startY

            // Smooth drop-down with subtle spring/overshoot
            pill.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(1.15f))
                .start()

            val dismissRunnable = Runnable {
                pill.animate()
                    .translationY(startY)
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        pill.visibility = View.GONE
                    }
                    .start()
            }
            notificationDismissRunnable = dismissRunnable
            pill.postDelayed(dismissRunnable, 2400)

            pill.setOnClickListener {
                pill.removeCallbacks(dismissRunnable)
                dismissRunnable.run()
            }
        }
    }

    // Burst Flood Protection & Import Feedback Cooldown
    private val recentImportTimestamps = mutableListOf<Long>()
    private var burstResetRunnable: Runnable? = null
    private var isBurstFloodDetected = false
    private var burstStartTime = 0L
    private var lastImportFeedbackTime = 0L

    // Dedicated SoundPool for 100% reliable instant sound feedback
    private var soundPool: SoundPool? = null
    private val soundMap = mutableMapOf<String, Int>()

    private fun setupSoundPool() {
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
        } catch (e: Exception) {
            // Ignore if SoundPool fails to initialize
        }
    }

    private fun triggerImportFeedback() {
        if (isFinishing || isDestroyed) return

        // 1. Reliable Sound (Using user-selected sound preset and volume level)
        if (MiniClipPreferences.isImportSoundEnabled(this)) {
            try {
                val soundType = MiniClipPreferences.getImportSoundType(this)
                val soundId = soundMap[soundType] ?: soundMap[MiniClipPreferences.SOUND_TYPE_CAMERA] ?: 0
                val volume = MiniClipPreferences.getImportSoundVolume(this) / 100f
                if (soundPool != null && soundId != 0) {
                    soundPool?.play(soundId, volume, volume, 1, 0, 1.0f)
                } else {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, volume) ?: binding.root.playSoundEffect(SoundEffectConstants.CLICK)
                }
            } catch (e: Exception) {
                binding.root.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }

        // 2. Vibration feedback (Using user-selected duration/intensity)
        if (MiniClipPreferences.isImportVibrationEnabled(this)) {
            try {
                val duration = MiniClipPreferences.getImportVibrationDuration(this).toLong().coerceAtLeast(20L)
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
            } catch (e: Exception) {
                // Ignore if device has no vibrator or security restriction
            }
        }
    }

    private fun triggerSubtleButtonHaptic() {
        if (isFinishing || isDestroyed) return
        try {
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
                        VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE),
                        audioAttributes
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30L)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun resetMiniClipDraft(isAutoTriggered: Boolean = false) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread

            if (isAutoTriggered) {
                lastImportFeedbackTime = System.currentTimeMillis()
            }

            val etPos = binding.includedSidePanel.etSidePanelPositivePrompt
            val etNeg = binding.includedSidePanel.etSidePanelNegativePrompt
            val etTitle = binding.includedSidePanel.etSidePanelTitle

            val currentPos = etPos.text?.toString()?.trim() ?: ""
            if (currentPos.isNotEmpty()) {
                lastHandledClipboardText = currentPos
            }

            miniClipDraft.images.clear()
            miniClipAdapter?.notifyDataSetChanged()
            updateMiniClipImagesUI()

            // Clear title & extra title & positive prompt (fixed title is permanently preserved)
            miniClipDraft.title = ""
            etTitle.setText("")
            miniClipDraft.extraTitle = ""
            binding.includedSidePanel.etSidePanelExtraTitle.setText("")
            miniClipDraft.positivePrompt = ""
            etPos.setText("")
            miniClipDraft.isPromptMixed = false
            binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.GONE
            updateFinalFilenamePreview()

            // Only clear negative prompt if unlocked!
            if (!isNegativeLocked) {
                miniClipDraft.negativePrompt = ""
                etNeg.setText("")
            }

            if (isAutoTriggered) {
                showTopPillNotification("تصاویر ناخواسته مدل پاکسازی شد")
            } else {
                if (!isNegativeLocked) {
                    showTopPillNotification("مینی‌کلیپ کاملاً پاک شد")
                } else {
                    showTopPillNotification("مینی‌کلیپ پاک شد (پرامپت منفی محفوظ ماند)")
                }
            }
            MiniClipPreferences.clearSlotDraft(this, getSlotIndex())
            updateSaveButtonState()
        }
    }

    fun closeProfileFromRam(slotIndexToClose: Int) {
        val wvToClose = webViewPool[slotIndexToClose] ?: return

        // بستن تب دوم این اسلات اگر باز باشد
        secondaryWebViews.remove(slotIndexToClose)?.let { tab2Wv ->
            try {
                binding.webViewContainer.removeView(tab2Wv)
                tab2Wv.onPause()
                tab2Wv.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        activeTabIndexPerSlot.remove(slotIndexToClose)

        // ۱. ذخیره پیش‌نویس و آخرین آدرس قبل از بستن از رم
        if (slotIndexToClose == activeSlotIndex) {
            persistCurrentDraft()
        }
        val lastUrl = wvToClose.url
        if (!lastUrl.isNullOrBlank()) {
            val profile = ProfileManager.getProfiles(this).find { it.slotIndex == slotIndexToClose }
            if (profile != null) {
                ProfileManager.updateLastUsedUrl(this, profile.id, lastUrl)
            }
        }

        // ۲. حذف WebView از لایوت و آزادسازی رم (بدون پاک کردن کوکی‌ها)
        destroyWebViewForSlot(slotIndexToClose)

        // ۳. اگر کاربر تبِ فعالِ فعلی را بسته است:
        if (slotIndexToClose == activeSlotIndex) {
            if (webViewPool.isNotEmpty()) {
                // سوییچ خودکار به آخرین پروفایل باز در رم
                val nextSlot = webViewPool.keys.last()
                val nextProfile = ProfileManager.getProfiles(this).find { it.slotIndex == nextSlot }
                if (nextProfile != null) {
                    switchToProfile(nextProfile)
                    showTopPillNotification("سوییچ به ${nextProfile.name}")
                    return
                }
            } else {
                // اگر هیچ پروفایلی در رم نماند، در همین اکانت بمان اما به صفحه خانه (HomeScreen) برو تا رم آزاد بماند
                showHomeScreen()
                binding.etUrl.setText("")
                updateProfileTabsBar()
                showTopPillNotification("پروفایل از رم بسته شد")
                return
            }
        }

        updateProfileTabsBar()
        showTopPillNotification("پروفایل از رم آزاد شد")
    }

    fun updateProfileTabsBar() {
        val container = binding.profileTabsContainer
        container.removeAllViews()

        val openSlots = webViewPool.keys.sorted()
        if (openSlots.isEmpty()) {
            binding.profileTabsScrollView.visibility = View.GONE
            return
        }
        binding.profileTabsScrollView.visibility = View.VISIBLE

        val allProfiles = ProfileManager.getProfiles(this)
        val density = resources.displayMetrics.density

        for (slotIndex in openSlots) {
            val profile = allProfiles.find { it.slotIndex == slotIndex }
            val profileName = profile?.name ?: "اکانت $slotIndex"
            val isActive = (slotIndex == activeSlotIndex)

            val tabLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (28 * density).toInt()
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                setPadding((10 * density).toInt(), 0, (6 * density).toInt(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    if (isActive) {
                        setColor(getColor(R.color.accent_blue))
                    } else {
                        setColor(Color.parseColor("#2A2D34"))
                        setStroke((1 * density).toInt(), Color.parseColor("#444852"))
                    }
                }
                setOnClickListener {
                    if (!isActive && profile != null) {
                        switchToProfile(profile)
                    }
                }
            }

            // نشانگر و نام پروفایل
            val tvTitle = TextView(this).apply {
                text = if (isActive) "● $profileName" else profileName
                setTextColor(Color.WHITE)
                textSize = 11.5f
                if (isActive) setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (120 * density).toInt()
                setPadding(0, 0, (6 * density).toInt(), 0)
            }
            tabLayout.addView(tvTitle)

            // دکمه ضربدر (✕) برای بستن از رم
            val btnClose = TextView(this).apply {
                text = "✕"
                setTextColor(if (isActive) Color.WHITE else Color.LTGRAY)
                textSize = 12f
                gravity = Gravity.CENTER
                val size = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setOnClickListener {
                    closeProfileFromRam(slotIndex)
                }
            }
            tabLayout.addView(btnClose)

            container.addView(tabLayout)
        }

        // دکمه + در انتهای تب‌ها برای باز کردن سریع دیالوگ پروفایل‌ها
        val btnAddTab = TextView(this).apply {
            text = "+"
            setTextColor(getColor(R.color.accent_blue))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val size = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#2A2D34"))
            }
            setOnClickListener {
                openProfileManager()
            }
        }
        container.addView(btnAddTab)
    }

    fun destroyWebViewForSlot(slotIndex: Int) {
        // بستن تب دوم این اسلات اگر باز باشد
        secondaryWebViews.remove(slotIndex)?.let { tab2Wv ->
            try {
                binding.webViewContainer.removeView(tab2Wv)
                tab2Wv.onPause()
                tab2Wv.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        activeTabIndexPerSlot.remove(slotIndex)

        val wv = webViewPool.remove(slotIndex)
        wv?.let {
            try {
                binding.webViewContainer.removeView(it)
                it.onPause()
                it.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        updateProfileTabsBar()
        updateBrowserTabsUI()
    }

    private fun createTabWebView(slotIndex: Int): WebView {
        val newWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                WebViewCompat.setProfile(newWebView, "profile_$slotIndex")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setupWebViewInstance(newWebView, slotIndex)
        return newWebView
    }

    fun openSecondTab(urlToLoad: String? = null) {
        if (secondaryWebViews.containsKey(activeSlotIndex)) {
            switchToTab(1)
            if (!urlToLoad.isNullOrBlank()) {
                secondaryWebViews[activeSlotIndex]?.loadUrl(urlToLoad)
            }
            return
        }

        val tab2Wv = createTabWebView(activeSlotIndex)
        secondaryWebViews[activeSlotIndex] = tab2Wv
        binding.webViewContainer.addView(tab2Wv)

        switchToTab(1)

        if (!urlToLoad.isNullOrBlank()) {
            tab2Wv.loadUrl(urlToLoad)
        } else {
            showHomeScreen()
        }
        updateBrowserTabsUI()
        showTopPillNotification("تب دوم باز شد")
    }

    fun closeSecondTab() {
        val tab2Wv = secondaryWebViews.remove(activeSlotIndex) ?: return
        try {
            binding.webViewContainer.removeView(tab2Wv)
            tab2Wv.onPause()
            tab2Wv.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        switchToTab(0)
        updateBrowserTabsUI()
        showTopPillNotification("تب دوم بسته شد")
    }

    fun switchToTab(tabIndex: Int) {
        if (tabIndex == 1 && !secondaryWebViews.containsKey(activeSlotIndex)) {
            return
        }

        activeTabIndexPerSlot[activeSlotIndex] = tabIndex

        val tab1Wv = webViewPool[activeSlotIndex]
        val tab2Wv = secondaryWebViews[activeSlotIndex]

        if (tabIndex == 0) {
            tab2Wv?.visibility = View.GONE
            tab2Wv?.onPause()
            if (!isHomeScreenActive) {
                tab1Wv?.visibility = View.VISIBLE
                tab1Wv?.onResume()
                val url = tab1Wv?.url ?: ""
                binding.etUrl.setText(url)
            }
        } else {
            tab1Wv?.visibility = View.GONE
            tab1Wv?.onPause()
            if (!isHomeScreenActive) {
                tab2Wv?.visibility = View.VISIBLE
                tab2Wv?.onResume()
                val url = tab2Wv?.url ?: ""
                binding.etUrl.setText(url)
            }
        }

        updateNavButtons()
        updateBrowserTabsUI()
    }

    fun handleWindowOpenRequest(resultMsg: android.os.Message): Boolean {
        val tab2Wv = secondaryWebViews[activeSlotIndex] ?: run {
            val wv = createTabWebView(activeSlotIndex)
            secondaryWebViews[activeSlotIndex] = wv
            binding.webViewContainer.addView(wv)
            wv
        }

        hideHomeScreen()
        switchToTab(1)

        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport != null) {
            transport.webView = tab2Wv
            resultMsg.sendToTarget()
            updateBrowserTabsUI()
            showTopPillNotification("تب جدید توسط سایت باز شد")
            return true
        }
        return false
    }

    fun handleWindowCloseRequest(window: WebView?) {
        if (window == secondaryWebViews[activeSlotIndex]) {
            closeSecondTab()
        }
    }

    fun updateBrowserTabsUI() {
        val isBarEnabled = MiniClipPreferences.isWebTabsBarEnabled(this)
        if (!isBarEnabled) {
            binding.layoutWebTabsBar.visibility = View.GONE
            return
        }
        binding.layoutWebTabsBar.visibility = View.VISIBLE

        val hasTab2 = secondaryWebViews.containsKey(activeSlotIndex)
        val activeTab = getActiveTabIndex()

        val tab1Wv = webViewPool[activeSlotIndex]
        val tab2Wv = secondaryWebViews[activeSlotIndex]

        val tab1Title = tab1Wv?.title?.takeIf { it.isNotBlank() }
            ?: try { Uri.parse(tab1Wv?.url ?: "").host?.removePrefix("www.") } catch (e: Exception) { null }
            ?: "تب ۱"
        binding.tvTab1Title.text = if (activeTab == 0) "● $tab1Title" else tab1Title
        binding.tab1Chip.setBackgroundResource(if (activeTab == 0) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
        binding.tvTab1Title.setTextColor(if (activeTab == 0) Color.WHITE else Color.LTGRAY)
        binding.tvTab1Title.setTypeface(null, if (activeTab == 0) Typeface.BOLD else Typeface.NORMAL)

        if (hasTab2) {
            binding.tab2Chip.visibility = View.VISIBLE
            binding.btnNewWebTab.visibility = View.GONE
            val tab2Title = tab2Wv?.title?.takeIf { it.isNotBlank() }
                ?: try { Uri.parse(tab2Wv?.url ?: "").host?.removePrefix("www.") } catch (e: Exception) { null }
                ?: "تب ۲"
            binding.tvTab2Title.text = if (activeTab == 1) "● $tab2Title" else tab2Title
            binding.tab2Chip.setBackgroundResource(if (activeTab == 1) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
            binding.tvTab2Title.setTextColor(if (activeTab == 1) Color.WHITE else Color.LTGRAY)
            binding.tvTab2Title.setTypeface(null, if (activeTab == 1) Typeface.BOLD else Typeface.NORMAL)
            binding.tvTabsCountIndicator.text = "۲ از ۲ تب"
        } else {
            binding.tab2Chip.visibility = View.GONE
            binding.btnNewWebTab.visibility = View.VISIBLE
            binding.tvTabsCountIndicator.text = "۱ از ۲ تب"
        }
    }

    fun getOrCreateWebViewForSlot(slotIndex: Int): WebView {
        webViewPool[slotIndex]?.let {
            return it
        }

        // LRU Eviction: maintain maximum 3 active WebViews in memory
        if (webViewPool.size >= 3) {
            val oldestSlot = webViewPool.keys.first()
            val oldestWv = webViewPool.remove(oldestSlot)
            oldestWv?.let { wv ->
                val lastUrl = wv.url
                if (!lastUrl.isNullOrBlank()) {
                    val oldProfile = ProfileManager.getProfiles(this).find { it.slotIndex == oldestSlot }
                    if (oldProfile != null) {
                        ProfileManager.updateLastUsedUrl(this, oldProfile.id, lastUrl)
                    }
                }
                binding.webViewContainer.removeView(wv)
                try {
                    wv.onPause()
                    wv.destroy()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val newWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                WebViewCompat.setProfile(newWebView, "profile_$slotIndex")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setupWebViewInstance(newWebView, slotIndex)

        newWebView.visibility = if (slotIndex == activeSlotIndex && !isHomeScreenActive) View.VISIBLE else View.GONE
        binding.webViewContainer.addView(newWebView)
        webViewPool[slotIndex] = newWebView
        updateProfileTabsBar()
        return newWebView
    }

    private fun setupWebViewInstance(webView: WebView, slotIndex: Int) {
        // Register JavaScript interface for AI image observer
        webView.addJavascriptInterface(
            MiniClipJSBridge { url, width, height ->
                runOnUiThread {
                    // Prevent background WebViews from injecting into the active slot's MiniClip draft
                    if (slotIndex != activeSlotIndex) return@runOnUiThread

                    val targetW = MiniClipPreferences.getTargetWidth(this)
                    val targetH = MiniClipPreferences.getTargetHeight(this)
                    val isExact = MiniClipPreferences.isExactDimensionMatch(this)

                    val matches = if (isExact) {
                        // Exact lock: neither larger nor smaller
                        Math.abs(width - targetW) <= 20 && Math.abs(height - targetH) <= 20
                    } else {
                        // Only larger than or equal to selected dimensions
                        val maxTarget = maxOf(targetW, targetH)
                        val minTarget = minOf(targetW, targetH)
                        val maxDim = maxOf(width, height)
                        val minDim = minOf(width, height)
                        (width >= targetW && height >= targetH) || (maxDim >= maxTarget && minDim >= minTarget)
                    }

                    if (matches) {
                        val isProtectionEnabled = MiniClipPreferences.isBurstProtectionEnabled(this)
                        val now = System.currentTimeMillis()

                        if (isProtectionEnabled) {
                            recentImportTimestamps.removeAll { now - it > 4000L }
                            recentImportTimestamps.add(now)

                            if (recentImportTimestamps.size > 4) {
                                lastImportFeedbackTime = now // Lock cooldown so no stray sound/vibration

                                if (!isBurstFloodDetected) {
                                    isBurstFloodDetected = true
                                    burstStartTime = now
                                    showTopPillNotification("تشخیص مدل‌های ناخواسته؛ پاکسازی تا ۳ ثانیه...")
                                }

                                // Collect the image quietly without individual toast spam
                                addCapturedImage(CapturedImage(imageUrl = url, width = width, height = height))

                                burstResetRunnable?.let { binding.root.removeCallbacks(it) }
                                val runnable = Runnable {
                                    if (MiniClipPreferences.isBurstProtectionEnabled(this@BaseBrowserActivity)) {
                                        resetMiniClipDraft(isAutoTriggered = true)
                                    }
                                    isBurstFloodDetected = false
                                    recentImportTimestamps.clear()
                                    burstResetRunnable = null
                                }
                                burstResetRunnable = runnable

                                // Wait 3 seconds after images stop arriving (capped at 5s total)
                                val elapsed = now - burstStartTime
                                val delay = if (elapsed > 4000L) 1000L else 3000L
                                binding.root.postDelayed(runnable, delay)
                                return@runOnUiThread
                            }
                        }

                        // Normal import (1 to 4 images from AI generation)
                        val added = addCapturedImage(CapturedImage(imageUrl = url, width = width, height = height))
                        if (added && !isBurstFloodDetected) {
                            showTopPillNotification("تصویر به مینی‌کلیپ اضافه شد")

                            // Instant feedback on first image, then 5-second cooldown
                            if (now - lastImportFeedbackTime >= 5000L) {
                                lastImportFeedbackTime = now
                                triggerImportFeedback()
                            }
                        }
                    }
                }
            },
            MiniClipJSBridge.BRIDGE_NAME
        )

        // Long-Press Context Menu on WebView for manual Image Import to Mini-Clip
        webView.setOnCreateContextMenuListener { menu, _, _ ->
            val hitTest = webView.hitTestResult
            val type = hitTest.type
            if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val imgUrl = hitTest.extra
                if (!imgUrl.isNullOrBlank()) {
                    menu.setHeaderTitle("گزینه‌های تصویر")
                    menu.add(0, 2001, 0, "📥 افزودن تصویر به مینی‌کلیپ").setOnMenuItemClickListener {
                        val added = addCapturedImage(CapturedImage(imageUrl = imgUrl))
                        if (added) {
                            triggerImportFeedback()
                        }
                        openMiniClipSidePanel()
                        true
                    }
                }
            }
        }

        WebViewHelper.setupWebView(
            webView = webView,
            context = this,
            isDesktopMode = isDesktopMode,
            onPageStarted = { url ->
                if (slotIndex == activeSlotIndex && webView == currentWebView) {
                    binding.progressBar.visibility = View.VISIBLE
                    if (!binding.etUrl.hasFocus()) {
                        binding.etUrl.setText(url)
                    }
                    updateNavButtons()
                }
            },
            onPageFinished = { url, _ ->
                if (slotIndex == activeSlotIndex && webView == currentWebView) {
                    binding.progressBar.visibility = View.GONE
                    if (!binding.etUrl.hasFocus()) {
                        binding.etUrl.setText(url)
                    }
                    updateNavButtons()

                    // Inject observer script ONLY for the active slot
                    if (MiniClipPreferences.isAutoImportEnabled(this)) {
                        val w = MiniClipPreferences.getTargetWidth(this)
                        val h = MiniClipPreferences.getTargetHeight(this)
                        val isExact = MiniClipPreferences.isExactDimensionMatch(this)
                        MiniClipJSBridge.injectObserverScript(webView, w, h, isExact)
                    }
                }
                updateBrowserTabsUI()

                // Apply video blocker script if enabled
                if (MiniClipPreferences.isBlockVideosEnabled(this)) {
                    WebViewHelper.injectVideoBlockerScript(webView)
                }
            },
            onProgressChanged = { progress ->
                if (slotIndex == activeSlotIndex && webView == currentWebView) {
                    binding.progressBar.progress = progress
                    binding.progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
                }
            },
            onCreateWindowRequest = { resultMsg ->
                handleWindowOpenRequest(resultMsg)
            },
            onCloseWindowRequest = { window ->
                handleWindowCloseRequest(window)
            }
        )
    }

    private fun setupMiniClipSidePanel() {
        val overlay = binding.includedSidePanel.layoutMiniClipSideOverlay
        val btnClose = binding.includedSidePanel.btnCloseSidePanel
        val btnClearDraft = binding.includedSidePanel.btnClearMiniClipDraft
        val btnPastePos = binding.includedSidePanel.btnSidePanelPastePositive
        val btnPromptMixer = binding.includedSidePanel.btnSidePanelPromptMixer
        val btnClearPos = binding.includedSidePanel.btnSidePanelClearPositive
        val btnToggleLock = binding.includedSidePanel.btnSidePanelToggleLockNegative
        val btnSave = binding.includedSidePanel.btnSidePanelSave
        val etPos = binding.includedSidePanel.etSidePanelPositivePrompt
        val etNeg = binding.includedSidePanel.etSidePanelNegativePrompt
        val rvImages = binding.includedSidePanel.rvSidePanelImages

        val btnToggleFixedTitle = binding.includedSidePanel.btnToggleFixedTitle
        val tvToggleFixedTitleLabel = binding.includedSidePanel.tvToggleFixedTitleLabel
        val layoutFixedTitleContainer = binding.includedSidePanel.layoutFixedTitleContainer
        val etFixedTitle = binding.includedSidePanel.etSidePanelFixedTitle
        val etTitle = binding.includedSidePanel.etSidePanelTitle

        val profile = ProfileManager.getProfiles(this).find { it.slotIndex == getSlotIndex() }
        val accountName = profile?.name ?: "اکانت ${getSlotIndex()}"
        binding.includedSidePanel.tvSidePanelAccountBadge.text = accountName

        // Setup horizontal recycler view for captured images
        rvImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        miniClipAdapter = MiniClipImageAdapter(
            images = miniClipDraft.images,
            coroutineScope = lifecycleScope,
            onRemoveClick = { position, _ ->
                miniClipAdapter?.removeAt(position)
                updateMiniClipImagesUI()
                persistCurrentDraft()
                showTopPillNotification("تصویر حذف شد")
            }
        )
        rvImages.adapter = miniClipAdapter

        // Close on clicking backdrop outside the panel card
        overlay.setOnClickListener {
            closeMiniClipSidePanel()
        }

        binding.includedSidePanel.layoutMiniClipSideCard.setOnClickListener {
            // Prevent click through
        }

        btnClose.setOnClickListener {
            closeMiniClipSidePanel()
        }

        // Toggle Expandable/Hidden Fixed Title Section
        btnToggleFixedTitle.setOnClickListener {
            if (layoutFixedTitleContainer.visibility == View.VISIBLE) {
                layoutFixedTitleContainer.visibility = View.GONE
                tvToggleFixedTitleLabel.setTextColor(getColor(R.color.accent_blue))
            } else {
                layoutFixedTitleContainer.visibility = View.VISIBLE
                tvToggleFixedTitleLabel.setTextColor(getColor(R.color.accent_green))
                etFixedTitle.requestFocus()
            }
        }

        etFixedTitle.doAfterTextChanged { s ->
            val text = s?.toString()?.trim() ?: ""
            MiniClipPreferences.setFixedTitlePrefix(this@BaseBrowserActivity, text, getSlotIndex())
            updateFinalFilenamePreview()
            if (text.isNotEmpty()) {
                tvToggleFixedTitleLabel.text = "📌 عنوان ثابت ($text)"
            } else {
                tvToggleFixedTitleLabel.text = "📌 عنوان ثابت"
            }
        }

        etTitle.doAfterTextChanged {
            updateFinalFilenamePreview()
        }

        binding.includedSidePanel.etSidePanelExtraTitle.doAfterTextChanged { s ->
            miniClipDraft.extraTitle = s?.toString()?.trim() ?: ""
            updateFinalFilenamePreview()
        }

        // Reset/Clear Draft button for this specific account
        btnClearDraft.setOnClickListener {
            triggerSubtleButtonHaptic()
            resetMiniClipDraft(isAutoTriggered = false)
        }

        btnPastePos.setOnClickListener {
            pastePositiveFromClipboard()
        }

        btnClearPos.setOnClickListener {
            val currentPos = etPos.text?.toString()?.trim() ?: ""
            if (currentPos.isNotEmpty()) {
                lastHandledClipboardText = currentPos
            }
            etPos.setText("")
            miniClipDraft.positivePrompt = ""
            miniClipDraft.isPromptMixed = false
            binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.GONE
            showTopPillNotification("پرامپت مثبت پاک شد")
            updateSaveButtonState()
        }

        btnPromptMixer.setOnClickListener {
            PromptMixerDialog(this) { extraTitle, injectedText, mode, commaIdx ->
                val currentPos = etPos.text?.toString()?.trim() ?: ""
                var isActuallyMixed = false
                if (currentPos.isNotEmpty() && injectedText.isNotBlank()) {
                    val merged = PromptMixerDialog.mergeText(currentPos, injectedText, mode, commaIdx)
                    etPos.setText(merged)
                    miniClipDraft.positivePrompt = merged
                    miniClipDraft.isPromptMixed = true
                    binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.VISIBLE

                    // کپی خودکار متن میکس شده به کلیپ‌بورد
                    copyMixedPromptToClipboardSafely(merged)
                    isActuallyMixed = true
                }
                if (extraTitle.isNotEmpty()) {
                    binding.includedSidePanel.etSidePanelExtraTitle.setText(extraTitle)
                    miniClipDraft.extraTitle = extraTitle
                }
                updateFinalFilenamePreview()
                persistCurrentDraft()
                updateSaveButtonState()

                if (isActuallyMixed) {
                    showYellowTopPillNotification("🔀 میکس شد • در باکس قرار گرفت و کپی شد")
                } else {
                    showTopPillNotification("تنظیمات میکسر ذخیره شد")
                }
            }.show()
        }

        etPos.doAfterTextChanged {
            updateSaveButtonState()
        }

        btnToggleLock.setOnClickListener {
            isNegativeLocked = !isNegativeLocked
            MiniClipPreferences.setNegativePromptLocked(this, isNegativeLocked, getSlotIndex())
            if (isNegativeLocked) {
                val currentText = etNeg.text.toString().trim()
                MiniClipPreferences.setLockedNegativePrompt(this, currentText, getSlotIndex())
            }
            updateNegativeLockStateUI()
        }

        btnSave.setOnClickListener {
            triggerSubtleButtonHaptic()
            if (miniClipDraft.images.isEmpty()) {
                showTopPillNotification("لطفاً ابتدا حداقل یک تصویر به مینی‌کلیپ اضافه کنید")
                return@setOnClickListener
            }

            val variableTitle = binding.includedSidePanel.etSidePanelTitle.text.toString().trim()
            val fixedPrefix = binding.includedSidePanel.etSidePanelFixedTitle.text.toString().trim()
            val extraTitle = binding.includedSidePanel.etSidePanelExtraTitle.text.toString().trim()
            val finalTitle = getCombinedTitle(fixedPrefix, variableTitle, extraTitle)

            val posPrompt = etPos.text.toString().trim()
            val negPrompt = if (isNegativeLocked) {
                MiniClipPreferences.getLockedNegativePrompt(this, getSlotIndex())
            } else {
                etNeg.text.toString().trim()
            }

            btnSave.isEnabled = false
            btnSave.text = "در حال ذخیره..."

            lifecycleScope.launch {
                val result = ImagePromptSaver.saveMultipleImagesAndPrompts(
                    context = this@BaseBrowserActivity,
                    images = ArrayList(miniClipDraft.images),
                    title = finalTitle,
                    positivePrompt = posPrompt,
                    negativePrompt = negPrompt
                )

                btnSave.isEnabled = true
                btnSave.text = "ذخیره"
                updateSaveButtonState()

                if (result.isSuccess) {
                    val count = result.getOrThrow()
                    showTopPillNotification("با موفقیت ذخیره شد: $count تصویر و پرامپت")

                    // Mark positive prompt as handled so it never auto-reverts
                    if (posPrompt.isNotEmpty()) {
                        lastHandledClipboardText = posPrompt
                    }

                    // Clear images & positive prompt & variable title & extra title after successful save
                    miniClipDraft.images.clear()
                    miniClipAdapter?.notifyDataSetChanged()
                    miniClipDraft.title = ""
                    binding.includedSidePanel.etSidePanelTitle.setText("")
                    miniClipDraft.extraTitle = ""
                    binding.includedSidePanel.etSidePanelExtraTitle.setText("")
                    miniClipDraft.isPromptMixed = false
                    binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.GONE
                    // NOTE: Fixed title prefix is deliberately NOT cleared ("با سیو کردن پاک نمیشه")
                    miniClipDraft.positivePrompt = ""
                    etPos.setText("")
                    MiniClipPreferences.clearSlotDraft(this@BaseBrowserActivity, getSlotIndex())

                    updateFinalFilenamePreview()
                    updateMiniClipImagesUI()
                    closeMiniClipSidePanel()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "خطا در ذخیره‌سازی"
                    showTopPillNotification(err)
                    btnSave.text = "ذخیره"
                    updateSaveButtonState()
                }
            }
        }
    }

    private fun openMiniClipSidePanel(extraImage: CapturedImage? = null) {
        binding.btnMiniClipEdgeTab.setBackgroundResource(R.drawable.bg_edge_tab)

        val overlay = binding.includedSidePanel.layoutMiniClipSideOverlay
        val etTitle = binding.includedSidePanel.etSidePanelTitle
        val etPos = binding.includedSidePanel.etSidePanelPositivePrompt
        val etNeg = binding.includedSidePanel.etSidePanelNegativePrompt
        val tvSavePath = binding.includedSidePanel.tvSidePanelSavePath

        // Update account badge
        val profile = ProfileManager.getProfiles(this).find { it.slotIndex == getSlotIndex() }
        val accountName = profile?.name ?: "اکانت ${getSlotIndex()}"
        binding.includedSidePanel.tvSidePanelAccountBadge.text = accountName

        if (extraImage != null) {
            addCapturedImage(extraImage)
        }

        // Refresh images list UI
        miniClipAdapter?.notifyDataSetChanged()
        updateMiniClipImagesUI()

        // 1. Restore Variable Title and Fixed Title Prefix and Extra Title
        etTitle.setText(miniClipDraft.title)
        binding.includedSidePanel.etSidePanelExtraTitle.setText(miniClipDraft.extraTitle)
        if (miniClipDraft.isPromptMixed) {
            binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.VISIBLE
        } else {
            binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.GONE
        }

        val fixedPrefix = MiniClipPreferences.getFixedTitlePrefix(this, getSlotIndex())
        if (binding.includedSidePanel.etSidePanelFixedTitle.text.toString() != fixedPrefix) {
            binding.includedSidePanel.etSidePanelFixedTitle.setText(fixedPrefix)
        }
        if (fixedPrefix.isNotEmpty()) {
            binding.includedSidePanel.tvToggleFixedTitleLabel.text = "📌 عنوان ثابت ($fixedPrefix)"
            binding.includedSidePanel.tvToggleFixedTitleLabel.setTextColor(getColor(R.color.accent_green))
        } else {
            binding.includedSidePanel.tvToggleFixedTitleLabel.text = "📌 عنوان ثابت"
            binding.includedSidePanel.tvToggleFixedTitleLabel.setTextColor(getColor(R.color.accent_blue))
        }
        updateFinalFilenamePreview()

        // 2. Restore Positive prompt
        if (miniClipDraft.positivePrompt.isNotBlank()) {
            etPos.setText(miniClipDraft.positivePrompt)
        } else {
            etPos.setText("")
        }
        updateSaveButtonState()

        // Auto-check latest clipboard if auto-mix is ON or manual clipboard mode is OFF
        if (MiniClipPreferences.isAutoMixClipboardEnabled(this) || !MiniClipPreferences.isManualClipboardOnly(this)) {
            checkAndReplaceClipboardIfNew()
        }

        // 3. Save Directory hint
        val saveDir = MiniClipPreferences.getSaveDirectory(this)
        tvSavePath.text = "مسیر ذخیره: ${saveDir.name}/[Title].webp + .txt"

        // 4. Negative Lock UI and prompt (slot-isolated)
        isNegativeLocked = MiniClipPreferences.isNegativePromptLocked(this, getSlotIndex())
        updateNegativeLockStateUI()
        if (isNegativeLocked) {
            val lockedNeg = MiniClipPreferences.getLockedNegativePrompt(this, getSlotIndex())
            if (lockedNeg.isNotBlank()) {
                etNeg.setText(lockedNeg)
            }
        } else if (miniClipDraft.negativePrompt.isNotBlank()) {
            etNeg.setText(miniClipDraft.negativePrompt)
        }

        // Show panel with smooth sliding entrance
        overlay.visibility = View.VISIBLE
        val card = binding.includedSidePanel.layoutMiniClipSideCard
        card.animate().cancel()
        card.post {
            val startX = if (card.width > 0) card.width.toFloat() else (330 * resources.displayMetrics.density)
            card.translationX = startX
            card.animate().translationX(0f).setDuration(160).start()
        }
    }

    private fun persistCurrentDraft() {
        try {
            val titleView = binding.includedSidePanel.etSidePanelTitle
            val extraTitleView = binding.includedSidePanel.etSidePanelExtraTitle
            val posView = binding.includedSidePanel.etSidePanelPositivePrompt
            val negView = binding.includedSidePanel.etSidePanelNegativePrompt
            if (titleView.text != null) {
                miniClipDraft.title = titleView.text.toString().trim()
            }
            if (extraTitleView.text != null) {
                miniClipDraft.extraTitle = extraTitleView.text.toString().trim()
            }
            if (posView.text != null) {
                miniClipDraft.positivePrompt = posView.text.toString().trim()
            }
            if (!isNegativeLocked && negView.text != null) {
                miniClipDraft.negativePrompt = negView.text.toString().trim()
            }
            MiniClipPreferences.saveSlotDraft(this, getSlotIndex(), miniClipDraft)
            MiniClipPreferences.setLastHandledClipboard(this, getSlotIndex(), lastHandledClipboardText)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closeMiniClipSidePanel() {
        hideKeyboard()
        persistCurrentDraft()

        val card = binding.includedSidePanel.layoutMiniClipSideCard
        card.animate().cancel()
        val targetX = if (card.width > 0) card.width.toFloat() else (330 * resources.displayMetrics.density)
        card.animate().translationX(targetX).setDuration(130).withEndAction {
            binding.includedSidePanel.layoutMiniClipSideOverlay.visibility = View.GONE
        }.start()
    }

    private fun isSidePanelOpen(): Boolean {
        return binding.includedSidePanel.layoutMiniClipSideOverlay.visibility == View.VISIBLE
    }

    private fun updateNegativeLockStateUI() {
        val btnToggle = binding.includedSidePanel.btnSidePanelToggleLockNegative
        val etNeg = binding.includedSidePanel.etSidePanelNegativePrompt
        if (isNegativeLocked) {
            btnToggle.text = "🔒 قفل است"
            btnToggle.setTextColor(getColor(R.color.accent_green))
            etNeg.setBackgroundResource(R.drawable.bg_locked_box)
            etNeg.isEnabled = false
            val locked = MiniClipPreferences.getLockedNegativePrompt(this, getSlotIndex())
            if (locked.isNotEmpty()) {
                etNeg.setText(locked)
            }
        } else {
            btnToggle.text = "🔓 باز (تغییرپذیر)"
            btnToggle.setTextColor(getColor(R.color.text_secondary))
            etNeg.setBackgroundResource(R.drawable.bg_edit_text)
            etNeg.isEnabled = true
        }
    }

    private fun getCombinedTitle(fixed: String, variable: String, extra: String = ""): String {
        val parts = listOf(
            fixed.trim().trimEnd('_', '-'),
            variable.trim().trim('_', '-'),
            extra.trim().trim('_', '-')
        ).filter { it.isNotEmpty() }
        return parts.joinToString("_")
    }

    private fun updateFinalFilenamePreview() {
        val fixed = binding.includedSidePanel.etSidePanelFixedTitle.text?.toString()?.trim() ?: ""
        val variable = binding.includedSidePanel.etSidePanelTitle.text?.toString()?.trim() ?: ""
        val extra = binding.includedSidePanel.etSidePanelExtraTitle.text?.toString()?.trim() ?: ""
        val combined = getCombinedTitle(fixed, variable, extra)
        if (combined.isNotEmpty()) {
            binding.includedSidePanel.tvSidePanelFinalFilenamePreview.text = "نام نهایی فایل: $combined.webp"
            binding.includedSidePanel.tvSidePanelFinalFilenamePreview.setTextColor(getColor(R.color.accent_green))
        } else {
            binding.includedSidePanel.tvSidePanelFinalFilenamePreview.text = "نام نهایی فایل: [عنوان خودکار / زمان]"
            binding.includedSidePanel.tvSidePanelFinalFilenamePreview.setTextColor(getColor(R.color.text_hint))
        }
    }

    private fun copyMixedPromptToClipboardSafely(mixedText: String) {
        if (mixedText.isBlank()) return
        // ۱. ابتدا متن میکس شده را به عنوان آخرین کلیپ‌بورد پردازش‌شده ثبت کن تا لیسنر دوباره آن را میکس نکند
        lastHandledClipboardText = mixedText
        MiniClipPreferences.setLastHandledClipboard(this, activeSlotIndex, mixedText)

        // ۲. قرار دادن متن جدید در کلیپ‌بورد سیستم برای Paste سریع توسط کاربر
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Mixed Prompt", mixedText)
        clipboard.setPrimaryClip(clip)
    }

    private fun extractFullPromptFromClipboard(clipData: ClipData?): String {
        if (clipData == null || clipData.itemCount == 0) return ""

        val rawBuilder = StringBuilder()
        for (i in 0 until clipData.itemCount) {
            val item = clipData.getItemAt(i)
            // دریافت متن کامل با حفظ تمام خطوط جدید (\n)
            val itemText = item.text?.toString() ?: item.coerceToText(this)?.toString() ?: ""
            if (itemText.isNotEmpty()) {
                if (rawBuilder.isNotEmpty() && !rawBuilder.endsWith("\n")) {
                    rawBuilder.append("\n")
                }
                rawBuilder.append(itemText)
            }
        }
        // فقط فاصله‌ها و خطوط خالیِ ابتدایِ اول و انتهایِ آخرِ کل متن را تریم کن، اما خطوط خالی وسط متن را کاملاً حفظ کن
        return rawBuilder.toString().trim()
    }

    private fun setupClipboardListener() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                if (MiniClipPreferences.isAutoMixClipboardEnabled(this@BaseBrowserActivity) ||
                    !MiniClipPreferences.isManualClipboardOnly(this@BaseBrowserActivity)) {
                    checkAndReplaceClipboardIfNew(isRealtimeListener = true)
                }
            }
            clipboard.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAndReplaceClipboardIfNew(isRealtimeListener: Boolean = false) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clipData = clipboard.primaryClip
            val rawText = extractFullPromptFromClipboard(clipData)
            if (rawText.isNotEmpty() && rawText != lastHandledClipboardText) {
                lastHandledClipboardText = rawText

                val isAutoMix = MiniClipPreferences.isAutoMixClipboardEnabled(this)
                if (isAutoMix) {
                    val injected = MiniClipPreferences.getMixerInjectedText(this)
                    val modeStr = MiniClipPreferences.getMixerPositionMode(this)
                    val mode = PromptMixerDialog.parseMode(modeStr)
                    val commaIdx = MiniClipPreferences.getMixerCommaIndex(this)
                    val extraTitle = MiniClipPreferences.getMixerExtraTitle(this)

                    val mixedPrompt = PromptMixerDialog.mergeText(rawText, injected, mode, commaIdx)

                    miniClipDraft.positivePrompt = mixedPrompt
                    binding.includedSidePanel.etSidePanelPositivePrompt.setText(mixedPrompt)

                    if (extraTitle.isNotEmpty()) {
                        binding.includedSidePanel.etSidePanelExtraTitle.setText(extraTitle)
                        miniClipDraft.extraTitle = extraTitle
                    }

                    miniClipDraft.isPromptMixed = true
                    binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.VISIBLE
                    updateFinalFilenamePreview()
                    persistCurrentDraft()
                    updateSaveButtonState()

                    // جایگزین کردن متن خام کلیپ‌بورد با متن میکس‌شده تا کاربر مستقیماً متن میکس‌شده را Paste کند
                    copyMixedPromptToClipboardSafely(mixedPrompt)

                    // نمایش اعلان زرد رنگ مخصوص میکس
                    showYellowTopPillNotification("🔀 میکس شد • در باکس قرار گرفت و کپی شد")
                } else {
                    miniClipDraft.positivePrompt = rawText
                    binding.includedSidePanel.etSidePanelPositivePrompt.setText(rawText)
                    miniClipDraft.isPromptMixed = false
                    binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.GONE
                    persistCurrentDraft()
                    showTopPillNotification("متن جدید کلیپ‌بورد جایگزین شد")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pastePositiveFromClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            val rawText = extractFullPromptFromClipboard(clipData)
            if (rawText.isNotEmpty()) {
                val isAutoMix = MiniClipPreferences.isAutoMixClipboardEnabled(this)
                if (isAutoMix) {
                    val injected = MiniClipPreferences.getMixerInjectedText(this)
                    val modeStr = MiniClipPreferences.getMixerPositionMode(this)
                    val mode = PromptMixerDialog.parseMode(modeStr)
                    val commaIdx = MiniClipPreferences.getMixerCommaIndex(this)
                    val extraTitle = MiniClipPreferences.getMixerExtraTitle(this)

                    val mixedPrompt = PromptMixerDialog.mergeText(rawText, injected, mode, commaIdx)

                    binding.includedSidePanel.etSidePanelPositivePrompt.setText(mixedPrompt)
                    miniClipDraft.positivePrompt = mixedPrompt

                    if (extraTitle.isNotEmpty()) {
                        binding.includedSidePanel.etSidePanelExtraTitle.setText(extraTitle)
                        miniClipDraft.extraTitle = extraTitle
                    }

                    miniClipDraft.isPromptMixed = true
                    binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.VISIBLE
                    updateFinalFilenamePreview()
                    persistCurrentDraft()
                    updateSaveButtonState()

                    // جایگزین کردن متن خام کلیپ‌بورد با متن میکس‌شده تا کاربر مستقیماً متن میکس‌شده را Paste کند
                    copyMixedPromptToClipboardSafely(mixedPrompt)

                    // نمایش اعلان زرد رنگ مخصوص میکس
                    showYellowTopPillNotification("🔀 میکس شد • در باکس قرار گرفت و کپی شد")
                } else {
                    binding.includedSidePanel.etSidePanelPositivePrompt.setText(rawText)
                    miniClipDraft.positivePrompt = rawText
                    lastHandledClipboardText = rawText
                    miniClipDraft.isPromptMixed = false
                    binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.GONE
                    persistCurrentDraft()
                    showTopPillNotification("پرامپت از کلیپ‌بورد درج شد")
                }
            } else {
                showTopPillNotification("کلیپ‌بورد خالی است")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (currentWebView.canGoBack()) 1.0f else 0.4f
        binding.btnForward.alpha = if (currentWebView.canGoForward()) 1.0f else 0.4f
    }

    private fun submitUrl() {
        val input = binding.etUrl.text.toString()
        val targetUrl = WebViewHelper.resolveUrl(input, this)
        hideKeyboard()
        loadUrl(targetUrl)
    }

    private fun loadUrl(url: String) {
        hideHomeScreen()
        currentWebView.loadUrl(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = currentFocus?.windowToken ?: binding.root.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
        currentFocus?.clearFocus()
        binding.etUrl.clearFocus()
    }

    private fun setupHomeScreen() {
        shortcutAdapter = HomeShortcutAdapter(
            items = ShortcutManager.getShortcuts(this),
            scope = lifecycleScope,
            onItemClick = { shortcut ->
                hideHomeScreen()
                loadUrl(shortcut.url)
            },
            onItemEdit = { shortcut ->
                showEditShortcutDialog(shortcut)
            },
            onItemDelete = { shortcut ->
                confirmDeleteShortcut(shortcut)
            },
            onItemsReordered = { updatedList ->
                ShortcutManager.saveShortcuts(this, updatedList)
            },
            onStartDrag = { viewHolder ->
                shortcutTouchHelper.startDrag(viewHolder)
            }
        )

        binding.includedHomeScreen.rvShortcuts.layoutManager = GridLayoutManager(this, 2)
        binding.includedHomeScreen.rvShortcuts.adapter = shortcutAdapter

        shortcutTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return shortcutAdapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        shortcutTouchHelper.attachToRecyclerView(binding.includedHomeScreen.rvShortcuts)

        binding.includedHomeScreen.btnAddShortcut.setOnClickListener {
            showEditShortcutDialog(null)
        }

        binding.includedHomeScreen.cardHomeSearch.setOnClickListener {
            binding.etUrl.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showHomeScreen() {
        isHomeScreenActive = true
        binding.includedHomeScreen.layoutHomeScreenRoot.visibility = View.VISIBLE
        for (wv in webViewPool.values) {
            wv.visibility = View.GONE
        }
        for (wv in secondaryWebViews.values) {
            wv.visibility = View.GONE
        }
        binding.etUrl.setText("")
        binding.etUrl.hint = "جستجو یا آدرس سایت..."
        binding.btnHome.setColorFilter(getColor(R.color.accent_blue))
        binding.progressBar.visibility = View.GONE

        // Refresh shortcuts
        val list = ShortcutManager.getShortcuts(this)
        shortcutAdapter.updateList(list)
    }

    private fun hideHomeScreen() {
        isHomeScreenActive = false
        binding.includedHomeScreen.layoutHomeScreenRoot.visibility = View.GONE
        currentWebView.visibility = View.VISIBLE
        binding.btnHome.clearColorFilter()
        binding.etUrl.hint = "جستجو یا آدرس وب..."
        val currentUrl = currentWebView.url
        if (!currentUrl.isNullOrBlank()) {
            binding.etUrl.setText(currentUrl)
        }
    }

    private fun showEditShortcutDialog(item: ShortcutItem?) {
        val isNew = (item == null)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_shortcut, null)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tvDialogShortcutTitle)
        val etName = dialogView.findViewById<EditText>(R.id.etShortcutName)
        val etUrl = dialogView.findViewById<EditText>(R.id.etShortcutUrl)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteShortcut)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelShortcut)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveShortcut)

        if (isNew) {
            tvDialogTitle.text = "افزودن میانبر جدید"
            btnDelete.visibility = View.GONE
            val curUrl = currentWebView.url
            if (!curUrl.isNullOrBlank() && curUrl.startsWith("http")) {
                etUrl.setText(curUrl)
                etName.setText(currentWebView.title ?: "")
            }
        } else {
            tvDialogTitle.text = "ویرایش میانبر"
            etName.setText(item!!.title)
            etUrl.setText(item.url)
            btnDelete.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            if (item != null) {
                dialog.dismiss()
                confirmDeleteShortcut(item)
            }
        }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            var urlStr = etUrl.text.toString().trim()

            if (urlStr.isEmpty()) {
                showTopPillNotification("لطفاً آدرس سایت را وارد کنید")
                return@setOnClickListener
            }

            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                urlStr = "https://$urlStr"
            }

            val finalName = if (name.isNotEmpty()) name else {
                try { Uri.parse(urlStr).host?.removePrefix("www.") ?: "سایت" } catch (e: Exception) { "سایت" }
            }

            if (isNew) {
                val newShortcut = ShortcutItem(
                    title = finalName,
                    url = urlStr
                )
                ShortcutManager.addShortcut(this, newShortcut)
                lifecycleScope.launch {
                    ShortcutManager.fetchAndCacheFavicon(this@BaseBrowserActivity, newShortcut)
                    shortcutAdapter.updateList(ShortcutManager.getShortcuts(this@BaseBrowserActivity))
                }
                showTopPillNotification("میانبر اضافه شد")
            } else {
                val updated = item!!.copy(title = finalName, url = urlStr)
                ShortcutManager.updateShortcut(this, updated)
                lifecycleScope.launch {
                    ShortcutManager.fetchAndCacheFavicon(this@BaseBrowserActivity, updated)
                    shortcutAdapter.updateList(ShortcutManager.getShortcuts(this@BaseBrowserActivity))
                }
                showTopPillNotification("میانبر ویرایش شد")
            }

            shortcutAdapter.updateList(ShortcutManager.getShortcuts(this))
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmDeleteShortcut(item: ShortcutItem) {
        AlertDialog.Builder(this)
            .setTitle("حذف میانبر")
            .setMessage("آیا میانبر «${item.title}» حذف شود؟")
            .setPositiveButton("بله، حذف شود") { _, _ ->
                ShortcutManager.deleteShortcut(this, item.id)
                shortcutAdapter.updateList(ShortcutManager.getShortcuts(this))
                showTopPillNotification("میانبر حذف شد")
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun openProfileManager() {
        ProfileManagerDialog(
            context = this,
            activeProfileId = currentProfileId,
            loadedSlotsInRam = webViewPool.keys.toSet(),
            onProfileSelected = { targetProfile ->
                switchToProfile(targetProfile)
            },
            onProfileCleared = { profile ->
                destroyWebViewForSlot(profile.slotIndex)
                if (profile.slotIndex == activeSlotIndex) {
                    showHomeScreen()
                    val newWv = getOrCreateWebViewForSlot(activeSlotIndex)
                    WebViewHelper.clearSessionAndCookies(newWv)
                }
                updateProfileTabsBar()
                updateProfileBadge()
            },
            onCloseFromRam = { profile ->
                closeProfileFromRam(profile.slotIndex)
            }
        ).show()
    }

    private fun switchToProfile(targetProfile: UserProfile) {
        val targetSlot = targetProfile.slotIndex
        if (targetSlot == activeSlotIndex && webViewPool.containsKey(targetSlot)) return

        // 1. Save last URL and freeze previous WebView in background
        persistCurrentDraft()
        secondaryWebViews[activeSlotIndex]?.let { tab2 ->
            tab2.visibility = View.GONE
            tab2.onPause()
        }
        webViewPool[activeSlotIndex]?.let { previousWv ->
            val currentUrl = previousWv.url
            if (!currentUrl.isNullOrBlank()) {
                ProfileManager.updateLastUsedUrl(this, currentProfileId, currentUrl)
            }
            previousWv.visibility = View.GONE
            previousWv.onPause() // Freeze rendering and scripts
        }

        // 2. Change active slot and profile
        activeSlotIndex = targetSlot
        currentProfileId = targetProfile.id
        ProfileManager.setActiveProfileId(this, targetProfile.id)

        // 3. Check if target WebView is already in RAM pool
        val isAlreadyInRam = webViewPool.containsKey(targetSlot)
        val targetWv = getOrCreateWebViewForSlot(targetSlot)

        // 4. Show and activate target WebView
        if (!isHomeScreenActive) {
            targetWv.visibility = View.VISIBLE
        }
        targetWv.onResume()

        if (!isAlreadyInRam || targetWv.url.isNullOrBlank()) {
            val urlToLoad = targetProfile.lastUsedUrl.ifBlank { "https://www.google.com" }
            if (urlToLoad.isBlank() || urlToLoad == "https://www.google.com") {
                showHomeScreen()
            } else {
                hideHomeScreen()
                targetWv.loadUrl(urlToLoad)
            }
        } else {
            if (!isHomeScreenActive) {
                binding.etUrl.setText(targetWv.url ?: "")
            }
        }

        // 5. Update MiniClip drafts and UI for the new active slot
        onSlotSwitchedUpdateUiAndMiniClip(activeSlotIndex, targetWv)

        // Ensure observer script is active on newly visible WebView
        if (MiniClipPreferences.isAutoImportEnabled(this)) {
            val w = MiniClipPreferences.getTargetWidth(this)
            val h = MiniClipPreferences.getTargetHeight(this)
            val isExact = MiniClipPreferences.isExactDimensionMatch(this)
            MiniClipJSBridge.injectObserverScript(targetWv, w, h, isExact)
        }
    }

    private fun onSlotSwitchedUpdateUiAndMiniClip(slotIndex: Int, wv: WebView) {
        updateProfileBadge()

        val profile = ProfileManager.getProfiles(this).find { it.slotIndex == slotIndex }
        binding.includedSidePanel.tvSidePanelAccountBadge.text = profile?.name ?: "اکانت $slotIndex"

        miniClipDraft = MiniClipPreferences.loadSlotDraft(this, slotIndex)
        lastHandledClipboardText = MiniClipPreferences.getLastHandledClipboard(this, slotIndex)
        isNegativeLocked = MiniClipPreferences.isNegativePromptLocked(this, slotIndex)

        binding.includedSidePanel.etSidePanelTitle.setText(miniClipDraft.title)
        binding.includedSidePanel.etSidePanelExtraTitle.setText(miniClipDraft.extraTitle)
        binding.includedSidePanel.etSidePanelPositivePrompt.setText(miniClipDraft.positivePrompt)

        if (isNegativeLocked) {
            val lockedNeg = MiniClipPreferences.getLockedNegativePrompt(this, slotIndex)
            if (lockedNeg.isNotEmpty()) {
                miniClipDraft.negativePrompt = lockedNeg
            }
        }
        binding.includedSidePanel.etSidePanelNegativePrompt.setText(miniClipDraft.negativePrompt)
        updateNegativeLockStateUI()

        val fixedPrefix = MiniClipPreferences.getFixedTitlePrefix(this, slotIndex)
        binding.includedSidePanel.etSidePanelFixedTitle.setText(fixedPrefix)
        if (fixedPrefix.isNotEmpty()) {
            binding.includedSidePanel.tvToggleFixedTitleLabel.text = "📌 عنوان ثابت ($fixedPrefix)"
            binding.includedSidePanel.tvToggleFixedTitleLabel.setTextColor(getColor(R.color.accent_green))
        } else {
            binding.includedSidePanel.tvToggleFixedTitleLabel.text = "📌 عنوان ثابت"
            binding.includedSidePanel.tvToggleFixedTitleLabel.setTextColor(getColor(R.color.accent_blue))
        }

        binding.includedSidePanel.tvSidePanelMixedBadge.visibility = if (miniClipDraft.isPromptMixed) View.VISIBLE else View.GONE

        // Refresh MiniClip images recycler adapter
        miniClipAdapter = MiniClipImageAdapter(
            images = miniClipDraft.images,
            coroutineScope = lifecycleScope,
            onRemoveClick = { position, _ ->
                miniClipAdapter?.removeAt(position)
                updateMiniClipImagesUI()
                persistCurrentDraft()
                showTopPillNotification("تصویر حذف شد")
            }
        )
        binding.includedSidePanel.rvSidePanelImages.adapter = miniClipAdapter

        updateMiniClipImagesUI()
        updateFinalFilenamePreview()
        updateNavButtons()
        updateProfileTabsBar()
        updateBrowserTabsUI()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (isDesktopMode) "نمای موبایل" else "نمای دسکتاپ")
        popup.menu.add(0, 2, 0, R.string.menu_clear_current)
        popup.menu.add(0, 3, 0, R.string.menu_open_in_browser)
        popup.menu.add(0, 4, 0, "تنظیمات")

        val curUrl = currentWebView.url
        if (!curUrl.isNullOrBlank() && curUrl.startsWith("http")) {
            popup.menu.add(0, 5, 0, "⭐ افزودن به میانبرهای خانه")
        }

        if (!secondaryWebViews.containsKey(activeSlotIndex)) {
            popup.menu.add(0, 9, 0, "➕ باز کردن تب دوم (حداکثر ۲ تب)")
        } else {
            val nextTabNum = if (getActiveTabIndex() == 0) 2 else 1
            popup.menu.add(0, 11, 0, "🔄 سوییچ به تب $nextTabNum")
            popup.menu.add(0, 10, 0, "❌ بستن تب دوم")
        }

        popup.menu.add(0, 6, 0, "🔀 میکسر پرامپت (ادغام متن)")
        popup.menu.add(0, 7, 0, "🖼️ گالری استودیو SeaArt")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    isDesktopMode = !isDesktopMode
                    setupWebViewInstance(currentWebView, activeSlotIndex)
                    currentWebView.reload()
                    val modeText = if (isDesktopMode) "حالت دسکتاپ فعال شد" else "حالت موبایل فعال شد"
                    showTopPillNotification(modeText)
                    true
                }
                2 -> {
                    WebViewHelper.clearSessionAndCookies(currentWebView)
                    showHomeScreen()
                    showTopPillNotification(getString(R.string.data_cleared_success))
                    true
                }
                3 -> {
                    val currentUrl = currentWebView.url
                    if (!currentUrl.isNullOrBlank()) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                        startActivity(browserIntent)
                    }
                    true
                }
                4 -> {
                    val settingsIntent = Intent(this, SettingsActivity::class.java)
                    startActivity(settingsIntent)
                    overridePendingTransition(0, 0)
                    true
                }
                5 -> {
                    val currentUrl = currentWebView.url
                    if (!currentUrl.isNullOrBlank()) {
                        val title = currentWebView.title ?: "سایت"
                        showEditShortcutDialog(ShortcutItem(title = title, url = currentUrl))
                    }
                    true
                }
                6 -> {
                    PromptMixerDialog(this) { extraTitle, injectedText, mode, commaIdx ->
                        val currentPos = binding.includedSidePanel.etSidePanelPositivePrompt.text?.toString()?.trim() ?: ""
                        var isActuallyMixed = false
                        if (currentPos.isNotEmpty() && injectedText.isNotBlank()) {
                            val merged = PromptMixerDialog.mergeText(currentPos, injectedText, mode, commaIdx)
                            binding.includedSidePanel.etSidePanelPositivePrompt.setText(merged)
                            miniClipDraft.positivePrompt = merged
                            miniClipDraft.isPromptMixed = true
                            binding.includedSidePanel.tvSidePanelMixedBadge.visibility = View.VISIBLE

                            // کپی خودکار متن میکس شده به کلیپ‌بورد
                            copyMixedPromptToClipboardSafely(merged)
                            isActuallyMixed = true
                        }
                        if (extraTitle.isNotEmpty()) {
                            binding.includedSidePanel.etSidePanelExtraTitle.setText(extraTitle)
                            miniClipDraft.extraTitle = extraTitle
                        }
                        updateFinalFilenamePreview()
                        persistCurrentDraft()
                        updateSaveButtonState()

                        if (isActuallyMixed) {
                            showYellowTopPillNotification("🔀 میکس شد • در باکس قرار گرفت و کپی شد")
                        } else {
                            showTopPillNotification("تنظیمات میکسر ذخیره شد")
                        }
                        openMiniClipSidePanel()
                    }.show()
                    true
                }
                7 -> {
                    openGallery()
                    true
                }
                9 -> {
                    openSecondTab()
                    true
                }
                10 -> {
                    closeSecondTab()
                    true
                }
                11 -> {
                    val nextTabIdx = if (getActiveTabIndex() == 0) 1 else 0
                    switchToTab(nextTabIdx)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openGallery() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.seaart.gallery")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "برنامه گالری سی‌آرت نصب نیست", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSidePanelOpen()) {
                    closeMiniClipSidePanel()
                } else if (getActiveTabIndex() == 1) {
                    val tab2Wv = secondaryWebViews[activeSlotIndex]
                    if (tab2Wv != null && tab2Wv.canGoBack()) {
                        tab2Wv.goBack()
                    } else {
                        closeSecondTab()
                    }
                } else if (isHomeScreenActive) {
                    finish()
                } else if (currentWebView.canGoBack()) {
                    currentWebView.goBack()
                } else {
                    showHomeScreen()
                }
            }
        })
    }

    override fun onDestroy() {
        if (isFinishing) {
            isSessionAuthenticated = false
        }
        try {
            soundPool?.release()
            soundPool = null
            soundMap.clear()
        } catch (e: Exception) {
            // Ignore
        }
        clipboardListener?.let { listener ->
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.removePrimaryClipChangedListener(listener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        for (wv in secondaryWebViews.values) {
            try {
                wv.onPause()
                wv.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        secondaryWebViews.clear()
        for (wv in webViewPool.values) {
            try {
                wv.onPause()
                wv.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        webViewPool.clear()
        super.onDestroy()
    }
}
