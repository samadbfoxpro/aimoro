package com.moror.browser.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moror.browser.R
import com.moror.browser.databinding.ActivityCacheManagerBinding
import com.moror.browser.engine.CacheAnalysis
import com.moror.browser.engine.CacheCategory
import com.moror.browser.engine.CategoryStat
import com.moror.browser.engine.FastCacheEngine
import com.moror.browser.engine.HostCacheStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CacheManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCacheManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        binding = ActivityCacheManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupActions()
        loadCacheData()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(0, 0)
        }

        binding.btnRefreshCache.setOnClickListener {
            loadCacheData()
        }
    }

    private fun setupActions() {
        binding.btnCleanAllCache.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("پاکسازی کامل حافظه کش")
                .setMessage("آیا مایلید تمام تصاویر، ویدیوها، فونت‌ها و استایل‌های ذخیره‌شده پاک شوند؟\n\n🔒 لاگین‌ها، کوکی‌ها و سشن‌های شما به هیچ وجه پاک نخواهند شد.")
                .setPositiveButton("بله، پاکسازی کامل شود") { _, _ ->
                    executeCleanAll()
                }
                .setNegativeButton("انصراف", null)
                .show()
        }
    }

    private fun loadCacheData() {
        binding.progressBarCache.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val analysis = FastCacheEngine.analyzeCache(this@CacheManagerActivity)
            withContext(Dispatchers.Main) {
                binding.progressBarCache.visibility = View.GONE
                updateUI(analysis)
            }
        }
    }

    private fun updateUI(analysis: CacheAnalysis) {
        // 1. Total Cache Header
        binding.tvTotalCacheSize.text = FastCacheEngine.formatFileSize(analysis.totalSizeBytes)
        binding.tvTotalCacheCount.text = "(%,d فایل)".format(analysis.totalFiles)

        // 2. Horizontal Colored Breakdown Bar
        binding.cacheBreakdownBar.setData(analysis.categoryStats)

        // 3. Legend Percentages
        val imgStat = analysis.categoryStats.find { it.category == CacheCategory.IMAGE }
        val vidStat = analysis.categoryStats.find { it.category == CacheCategory.VIDEO }
        val fontStat = analysis.categoryStats.find { it.category == CacheCategory.FONT }
        val styleStat = analysis.categoryStats.find { it.category == CacheCategory.STYLE_CODE }
        val otherStat = analysis.categoryStats.find { it.category == CacheCategory.OTHER }

        binding.tvLegendImagePct.text = "تصاویر: %.0f٪".format(imgStat?.percentOfTotal ?: 0f)
        binding.tvLegendVideoPct.text = "ویدیو: %.0f٪".format(vidStat?.percentOfTotal ?: 0f)
        binding.tvLegendFontPct.text = "فونت: %.0f٪".format(fontStat?.percentOfTotal ?: 0f)
        binding.tvLegendStylePct.text = "استایل: %.0f٪".format(styleStat?.percentOfTotal ?: 0f)
        binding.tvLegendOtherPct.text = "سایر: %.0f٪".format(otherStat?.percentOfTotal ?: 0f)

        // 4. Categories List
        populateCategories(analysis.categoryStats)

        // 5. Top Hosts
        populateTopHosts(analysis.topHosts)
    }

    private fun populateCategories(stats: List<CategoryStat>) {
        val container = binding.layoutCategoriesContainer
        container.removeAllViews()

        val inflater = LayoutInflater.from(this)

        for (stat in stats) {
            val itemView = inflater.inflate(R.layout.item_cache_category, container, false)
            val viewColor = itemView.findViewById<View>(R.id.viewCategoryColor)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvCategoryTitle)
            val tvPercent = itemView.findViewById<TextView>(R.id.tvCategoryPercent)
            val tvExtDesc = itemView.findViewById<TextView>(R.id.tvCategoryExtDesc)
            val tvSizeCount = itemView.findViewById<TextView>(R.id.tvCategorySizeCount)
            val btnClean = itemView.findViewById<Button>(R.id.btnCleanThisCategory)

            try {
                viewColor.setBackgroundColor(Color.parseColor(stat.category.colorHex))
            } catch (e: Exception) {
                viewColor.setBackgroundColor(getColor(R.color.accent_blue))
            }

            tvTitle.text = stat.category.titleFa
            tvPercent.text = "%.0f٪".format(stat.percentOfTotal)
            tvExtDesc.text = stat.category.extensionsDesc
            tvSizeCount.text = "${FastCacheEngine.formatFileSize(stat.sizeBytes)} (%,d فایل)".format(stat.fileCount)

            if (stat.sizeBytes == 0L) {
                btnClean.isEnabled = false
                btnClean.alpha = 0.4f
                btnClean.text = "خالی"
            } else {
                btnClean.isEnabled = true
                btnClean.alpha = 1.0f
                btnClean.text = "پاکسازی"
                btnClean.setOnClickListener {
                    confirmCleanCategory(stat)
                }
            }

            container.addView(itemView)
        }
    }

    private fun confirmCleanCategory(stat: CategoryStat) {
        val sizeFormatted = FastCacheEngine.formatFileSize(stat.sizeBytes)
        AlertDialog.Builder(this)
            .setTitle("پاکسازی ${stat.category.titleFa}")
            .setMessage("آیا مایلید تمام فایل‌های مربوط به ${stat.category.titleFa} به حجم $sizeFormatted پاک شوند؟\n\nاین کار باعث خروج از اکانت یا حذف اطلاعات شخصی نمی‌شود.")
            .setPositiveButton("بله، پاک شود") { _, _ ->
                executeCleanCategory(stat.category)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun executeCleanCategory(category: CacheCategory) {
        binding.progressBarCache.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val freedBytes = FastCacheEngine.clearCategory(this@CacheManagerActivity, category)
            val freedFormatted = FastCacheEngine.formatFileSize(freedBytes)
            val updatedAnalysis = FastCacheEngine.analyzeCache(this@CacheManagerActivity)

            withContext(Dispatchers.Main) {
                binding.progressBarCache.visibility = View.GONE
                updateUI(updatedAnalysis)
                showTopPillNotification("$freedFormatted از حافظه کش آزاد شد")
            }
        }
    }

    private fun populateTopHosts(hosts: List<HostCacheStat>) {
        val container = binding.layoutTopHostsContainer
        container.removeAllViews()

        if (hosts.isEmpty() || hosts.all { it.sizeBytes == 0L }) {
            binding.cardTopHosts.visibility = View.GONE
            binding.tvTopHostsTitle.visibility = View.GONE
            return
        }

        binding.cardTopHosts.visibility = View.VISIBLE
        binding.tvTopHostsTitle.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)

        for (hostStat in hosts) {
            if (hostStat.sizeBytes == 0L) continue
            val rowView = inflater.inflate(R.layout.item_cache_host, container, false)
            val tvName = rowView.findViewById<TextView>(R.id.tvHostName)
            val tvSize = rowView.findViewById<TextView>(R.id.tvHostSize)
            val btnClean = rowView.findViewById<ImageButton>(R.id.btnCleanHost)

            tvName.text = hostStat.host
            tvSize.text = FastCacheEngine.formatFileSize(hostStat.sizeBytes)

            btnClean.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("پاکسازی کش سایت")
                    .setMessage("آیا مایلید فایل‌های کش مربوط به ${hostStat.host} پاک شوند؟")
                    .setPositiveButton("بله، پاک شود") { _, _ ->
                        executeCleanHost(hostStat.host)
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            }

            container.addView(rowView)
        }
    }

    private fun executeCleanHost(host: String) {
        binding.progressBarCache.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val freed = FastCacheEngine.clearHost(this@CacheManagerActivity, host)
            val freedFormatted = FastCacheEngine.formatFileSize(freed)
            val updatedAnalysis = FastCacheEngine.analyzeCache(this@CacheManagerActivity)

            withContext(Dispatchers.Main) {
                binding.progressBarCache.visibility = View.GONE
                updateUI(updatedAnalysis)
                showTopPillNotification("کش $host به حجم $freedFormatted پاک شد")
            }
        }
    }

    private fun executeCleanAll() {
        binding.progressBarCache.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val totalFreed = FastCacheEngine.clearAllPermanentCache(this@CacheManagerActivity)
            val freedFormatted = FastCacheEngine.formatFileSize(totalFreed)
            val updatedAnalysis = FastCacheEngine.analyzeCache(this@CacheManagerActivity)

            withContext(Dispatchers.Main) {
                binding.progressBarCache.visibility = View.GONE
                updateUI(updatedAnalysis)
                showTopPillNotification("تمام فایل‌های کش پاک شدند ($freedFormatted آزاد شد)")
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
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
