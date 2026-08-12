package com.learnlayout.mp_3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Encapsula exclusivamente el comportamiento/animaciones del BottomSheet del
 * reproductor. PlayerPanelController queda encargado del contenido y de los
 * controles, mientras esta clase maneja estados, insets y transiciones.
 */
class PlayerPanelAnimationController(
    private val activity: AppCompatActivity,
    private val playerPanel: FrameLayout,
    private val groupExpanded: View,
    private val groupMini: View,
    private val audioSpectrumView: AudioSpectrumView,
    private val btnPanelBack: View,
    private val btnPanelSleepTimer: View,
    private val onExpanded: () -> Unit,
    private val onCollapsed: () -> Unit
) {
    companion object {
        private const val TAG = "MP3_PANEL"
        private const val EXPAND_ANIM_DURATION_MS = 320L
    }

    private lateinit var behavior: BottomSheetBehavior<FrameLayout>
    private var coldExpandInProgress = false
    private var expandAnimator: ValueAnimator? = null

    private val baseGroupMiniPaddingBottom = groupMini.paddingBottom
    private val baseGroupExpandedPaddingBottom = groupExpanded.paddingBottom
    private val baseBtnPanelBackMarginTop =
        (btnPanelBack.layoutParams as ConstraintLayout.LayoutParams).topMargin
    private val baseBtnPanelSleepTimerMarginTop =
        (btnPanelSleepTimer.layoutParams as ConstraintLayout.LayoutParams).topMargin

    val isReady: Boolean
        get() = ::behavior.isInitialized

    val isExpanded: Boolean
        get() = isReady && behavior.state == BottomSheetBehavior.STATE_EXPANDED

    fun setup(onMiniClicked: () -> Unit = { smoothExpand() }) {
        behavior = BottomSheetBehavior.from(playerPanel)
        behavior.isHideable = false
        behavior.skipCollapsed = false

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        groupMini.alpha = 0f
                        groupExpanded.alpha = 1f
                        groupMini.visibility = View.INVISIBLE
                        groupExpanded.visibility = View.VISIBLE

                        if (coldExpandInProgress) return

                        audioSpectrumView.start()
                        onExpanded()
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        groupMini.alpha = 1f
                        groupExpanded.alpha = 0f
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.INVISIBLE
                        updatePeekHeight()
                        audioSpectrumView.stop()
                        onCollapsed()
                    }
                    else -> {
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.VISIBLE
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                groupMini.alpha = (1f - (progress / 0.5f)).coerceIn(0f, 1f)
                groupExpanded.alpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
            }
        })

        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        groupMini.alpha = 1f
        groupExpanded.alpha = 0f
        groupMini.visibility = View.VISIBLE
        groupExpanded.visibility = View.INVISIBLE
        audioSpectrumView.stop()

        groupMini.setOnClickListener { onMiniClicked() }
        btnPanelBack.setOnClickListener { collapse() }
    }

    fun setDraggable(draggable: Boolean) {
        if (isReady) behavior.setDraggable(draggable)
    }

    fun collapse() {
        expandAnimator?.cancel()
        coldExpandInProgress = false
        playerPanel.translationY = 0f
        if (isReady) {
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun expandWhenReady() {
        if (!isReady || behavior.state == BottomSheetBehavior.STATE_EXPANDED) return

        expandAnimator?.cancel()
        coldExpandInProgress = true
        behavior.isDraggable = false

        groupMini.alpha = 0f
        groupMini.visibility = View.INVISIBLE
        groupExpanded.alpha = 1f
        groupExpanded.visibility = View.VISIBLE

        val offscreenOffset = maxOf(
            playerPanel.height,
            playerPanel.rootView.height,
            activity.resources.displayMetrics.heightPixels
        ).toFloat()
        playerPanel.translationY = offscreenOffset
        playerPanel.visibility = View.GONE
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        playerPanel.visibility = View.VISIBLE
        playerPanel.doOnLayoutCompat { coldExpand() }
    }

    fun smoothExpand() {
        if (!isReady || behavior.state == BottomSheetBehavior.STATE_EXPANDED) return

        val panelHeight = playerPanel.height
        val startOffset = (panelHeight - behavior.peekHeight).toFloat()
        if (startOffset <= 0f) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            return
        }

        expandAnimator?.cancel()
        coldExpandInProgress = false
        behavior.isDraggable = false

        groupMini.visibility = View.VISIBLE
        groupMini.alpha = 1f
        groupExpanded.visibility = View.VISIBLE
        groupExpanded.alpha = 0f

        playerPanel.translationY = startOffset
        playerPanel.visibility = View.GONE
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        playerPanel.visibility = View.VISIBLE

        expandAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = EXPAND_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                playerPanel.translationY = value
                val progress = 1f - (value / startOffset).coerceIn(0f, 1f)
                groupMini.alpha = (1f - (progress / 0.5f)).coerceIn(0f, 1f)
                groupExpanded.alpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                if (progress > 0.4f) groupExpanded.visibility = View.VISIBLE
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (expandAnimator !== animation) return
                    playerPanel.translationY = 0f
                    groupMini.alpha = 0f
                    groupMini.visibility = View.INVISIBLE
                    groupExpanded.alpha = 1f
                    groupExpanded.visibility = View.VISIBLE
                    behavior.isDraggable = true
                    expandAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator !== animation) return
                    playerPanel.translationY = 0f
                    behavior.isDraggable = true
                    expandAnimator = null
                }
            })
            start()
        }
    }

    fun updatePeekHeight() {
        groupMini.post {
            val height = groupMini.height
            if (isReady && height > 0 && height != behavior.peekHeight) {
                behavior.peekHeight = height
                playerPanel.requestLayout()
            }
        }
    }

    fun applyTopInset(systemBarsTop: Int) {
        setTopMargin(btnPanelBack, baseBtnPanelBackMarginTop + systemBarsTop)
        setTopMargin(btnPanelSleepTimer, baseBtnPanelSleepTimerMarginTop + systemBarsTop)
    }

    fun applyBottomInset(systemBarsBottom: Int) {
        groupMini.setPadding(
            groupMini.paddingLeft,
            groupMini.paddingTop,
            groupMini.paddingRight,
            baseGroupMiniPaddingBottom + systemBarsBottom
        )
        groupExpanded.setPadding(
            groupExpanded.paddingLeft,
            groupExpanded.paddingTop,
            groupExpanded.paddingRight,
            baseGroupExpandedPaddingBottom + systemBarsBottom
        )
    }

    private fun setTopMargin(view: View, marginPx: Int) {
        val params = view.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (params.topMargin == marginPx) return
        params.topMargin = marginPx
        view.layoutParams = params
    }

    private fun coldExpand() {
        if (!isReady || !coldExpandInProgress) return
        if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            coldExpandInProgress = false
            behavior.isDraggable = true
            playerPanel.translationY = 0f
            return
        }

        val startOffset = playerPanel.height.toFloat()
        if (startOffset <= 0f) {
            playerPanel.translationY = 0f
            coldExpandInProgress = false
            behavior.isDraggable = true
            audioSpectrumView.start()
            onExpanded()
            return
        }

        playerPanel.translationY = startOffset
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = EXPAND_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                playerPanel.translationY = anim.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (expandAnimator !== animation) return
                    playerPanel.translationY = 0f
                    expandAnimator = null
                    coldExpandInProgress = false
                    behavior.isDraggable = true
                    audioSpectrumView.start()
                    onExpanded()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator !== animation) return
                    expandAnimator = null
                    coldExpandInProgress = false
                    playerPanel.translationY = 0f
                    behavior.isDraggable = true
                }
            })
            start()
        }
    }

    private fun View.doOnLayoutCompat(action: () -> Unit) {
        if (isLaidOut) post(action) else {
            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    removeOnLayoutChangeListener(this)
                    action()
                }
            })
        }
    }
}