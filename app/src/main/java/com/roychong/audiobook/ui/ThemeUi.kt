package com.roychong.audiobook.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import android.widget.TextView
import com.roychong.audiobook.ReaderTheme

/** 把主题颜色应用到 Activity 的窗口和若干控件上。 */
object ThemeUi {

    fun apply(activity: Activity, theme: ReaderTheme) {
        val window = activity.window
        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        window.decorView.setBackgroundColor(theme.bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = window.insetsController
            if (c != null) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                if (theme.lightStatusBar) c.setSystemBarsAppearance(mask, mask)
                else c.setSystemBarsAppearance(0, mask)
            }
        } else {
            @Suppress("DEPRECATION")
            if (theme.lightStatusBar) {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
    }

    fun text(v: TextView, color: Int) {
        v.setTextColor(color)
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
