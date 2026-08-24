package com.learnlayout.mp_3

import androidx.annotation.DrawableRes

data class OnboardingPage(
    @DrawableRes val iconRes: Int,
    val title: String,
    val description: String
)