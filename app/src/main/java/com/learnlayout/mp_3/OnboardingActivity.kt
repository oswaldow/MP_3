package com.learnlayout.mp_3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.learnlayout.mp_3.databinding.ActivityOnboardingBinding

// Se muestra una sola vez, la primera vez que alguien instala y abre la
// app (ver SettingsRepository.isOnboardingCompleted / SongListActivity.onCreate).
// Explica con imagenes/iconos de la propia app las funciones principales:
// caratulas automaticas, ecualizador, letras sincronizadas, cola de
// reproduccion, playlists, widget de inicio y lector de WhatsApp.
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val pages by lazy {
        listOf(
            OnboardingPage(
                iconRes = R.drawable.ic_music_note,
                title = getString(R.string.onboarding_title_welcome),
                description = getString(R.string.onboarding_desc_welcome)
            ),
            OnboardingPage(
                iconRes = R.drawable.ic_equalizer,
                title = getString(R.string.onboarding_title_equalizer),
                description = getString(R.string.onboarding_desc_equalizer)
            ),
            OnboardingPage(
                iconRes = R.drawable.ic_lyrics,
                title = getString(R.string.onboarding_title_lyrics),
                description = getString(R.string.onboarding_desc_lyrics)
            ),
            OnboardingPage(
                iconRes = R.drawable.ic_queue_music,
                title = getString(R.string.onboarding_title_queue),
                description = getString(R.string.onboarding_desc_queue)
            ),
            OnboardingPage(
                iconRes = R.drawable.ic_playlist,
                title = getString(R.string.onboarding_title_playlists),
                description = getString(R.string.onboarding_desc_playlists)
            ),
            OnboardingPage(
                iconRes = R.drawable.ic_widgets,
                title = getString(R.string.onboarding_title_widget),
                description = getString(R.string.onboarding_desc_widget)
            ),
            OnboardingPage(
                iconRes = R.drawable.ic_chat,
                title = getString(R.string.onboarding_title_whatsapp),
                description = getString(R.string.onboarding_desc_whatsapp)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.vpOnboarding.adapter = OnboardingPagerAdapter(pages)

        setupDotsIndicator()
        updateControlsForPage(0)

        binding.vpOnboarding.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateDotsIndicator(position)
                updateControlsForPage(position)
            }
        })

        binding.btnSkip.setOnClickListener { finishOnboarding() }

        binding.btnNext.setOnClickListener {
            val nextItem = binding.vpOnboarding.currentItem + 1
            if (nextItem < pages.size) {
                binding.vpOnboarding.currentItem = nextItem
            } else {
                finishOnboarding()
            }
        }
    }

    private fun setupDotsIndicator() {
        binding.llDotsIndicator.removeAllViews()
        val dotSize = dpToPx(8)
        val dotMargin = dpToPx(4)

        pages.indices.forEach { index ->
            val dot = View(this)
            val params = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginStart = dotMargin
                marginEnd = dotMargin
            }
            dot.layoutParams = params
            dot.setBackgroundResource(
                if (index == 0) R.drawable.dot_indicator_active else R.drawable.dot_indicator_inactive
            )
            binding.llDotsIndicator.addView(dot)
        }
    }

    private fun updateDotsIndicator(selectedIndex: Int) {
        val dotsContainer = binding.llDotsIndicator
        for (i in 0 until dotsContainer.childCount) {
            dotsContainer.getChildAt(i).setBackgroundResource(
                if (i == selectedIndex) R.drawable.dot_indicator_active else R.drawable.dot_indicator_inactive
            )
        }
    }

    private fun updateControlsForPage(position: Int) {
        val isLastPage = position == pages.size - 1
        binding.btnNext.text = if (isLastPage) {
            getString(R.string.onboarding_start)
        } else {
            getString(R.string.onboarding_next)
        }
        binding.btnSkip.visibility = if (isLastPage) View.INVISIBLE else View.VISIBLE
    }

    private fun finishOnboarding() {
        SettingsRepository.setOnboardingCompleted(this, true)
        startActivity(Intent(this, SongListActivity::class.java))
        finish()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}