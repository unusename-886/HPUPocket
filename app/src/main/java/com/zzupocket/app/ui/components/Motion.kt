package com.zzupocket.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 试运行体验：状态切换（加载 → 空态 → 有数据 → 错误）统一用"淡入 + 轻微上移"，
 * 避免数据刷新时整块内容硬切、跳变。
 *
 * 时长压在 180~220ms：够看清过渡，又不拖慢课表这种高频切换页面。
 * 尊重系统"减少动画"设置由 Compose 的 [AnimatedVisibility] 自动处理。
 */
private const val DURATION = 200
private val EASING = FastOutSlowInEasing

/** 淡入 + 从下方 8dp 轻微滑入 */
fun enterFadeSlide(): EnterTransition =
    fadeIn(animationSpec = tween(DURATION, easing = EASING)) +
        slideInVertically(
            animationSpec = tween(DURATION, easing = EASING),
            initialOffsetY = { it / 8 },
        )

/**
 * 带进入动画的可见性包装。用于包裹"根据状态显示"的内容块：
 *
 * ```
 * FadeInVisible(visible = state.loading) {
 *     CircularProgressIndicator()
 * }
 * ```
 */
@Composable
fun FadeInVisible(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enterFadeSlide(),
        exit = fadeOut(animationSpec = tween(120)),
    ) {
        content()
    }
}
