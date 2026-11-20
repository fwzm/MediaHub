package com.mediahub.feature.detail

/**
 * Detail screen layout tokens (audit D6/D7).
 *
 * The detail screen used to mix 20dp gutters (hero content, primary CTA, synopsis)
 * with 16dp gutters (director/cast/season sections). D7 unifies every section on
 * [DETAIL_SCREEN_GUTTER_DP]. D6 pins the primary CTA to a fixed 52dp height and
 * 16dp outer padding instead of the previous 54dp / 20dp "fat" button.
 */
internal const val DETAIL_SCREEN_GUTTER_DP = 16

internal const val DETAIL_PRIMARY_CTA_HEIGHT_DP = 52

internal const val DETAIL_PRIMARY_CTA_PADDING_DP = 16