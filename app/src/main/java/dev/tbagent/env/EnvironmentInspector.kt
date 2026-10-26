package dev.tbagent.env

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.tbagent.BuildConfig
import dev.tbagent.Graph
import dev.tbagent.a11y.ControllerService
import dev.tbagent.capture.EchoSpeaker
import dev.tbagent.core.TalkBackPackages

data class SetupStatus(
    val talkBackEnabled: Boolean,
    val talkBackPackage: String?,
    val talkBackVersion: String?,
    val touchExploration: Boolean,
    val controllerEnabled: Boolean,
    val controllerConnected: Boolean,
    val defaultEngine: String?,
    val ourEngineSelected: Boolean,
    val echoEngine: String?,
    val chromeVersion: String?,
    val talkBackUtterances: Int,
) {
    val chromeInstalled: Boolean get() = chromeVersion != null
}

object EnvironmentInspector {

    fun status(ctx: Context): SetupStatus {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val enabledPackages = enabled.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
        val talkBack = enabledPackages.firstOrNull { TalkBackPackages.isTalkBack(it) }
        val defaultEngine = Settings.Secure.getString(ctx.contentResolver, "tts_default_synth")
        return SetupStatus(
            talkBackEnabled = talkBack != null,
            talkBackPackage = talkBack,
            talkBackVersion = (talkBack ?: TalkBackPackages.KNOWN.firstOrNull { version(ctx, it) != null })
                ?.let { pkg -> version(ctx, pkg)?.let { "$pkg $it" } },
            touchExploration = am.isTouchExplorationEnabled,
            controllerEnabled = ctx.packageName in enabledPackages,
            controllerConnected = ControllerService.instance != null,
            defaultEngine = defaultEngine,
            ourEngineSelected = defaultEngine == ctx.packageName,
            echoEngine = EchoSpeaker.pickEngine(ctx),
            chromeVersion = version(ctx, ControllerService.CHROME),
            talkBackUtterances = Graph.utterances.snapshot().count { it.isFromTalkBack },
        )
    }

    fun version(ctx: Context, pkg: String): String? = try {
        val info = ctx.packageManager.getPackageInfo(pkg, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun environmentMap(ctx: Context): Map<String, String> {
        val s = status(ctx)
        return linkedMapOf(
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "securityPatch" to Build.VERSION.SECURITY_PATCH,
            "app" to "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}",
            "talkBack" to (s.talkBackVersion ?: "no instalado"),
            "talkBackEnabled" to s.talkBackEnabled.toString(),
            "touchExploration" to s.touchExploration.toString(),
            "chrome" to (s.chromeVersion ?: "no instalado"),
            "defaultTtsEngine" to (s.defaultEngine ?: "desconocido"),
            "captureEngineSelected" to s.ourEngineSelected.toString(),
            "echoEngine" to (s.echoEngine ?: "ninguno"),
            "captureValidatedByTester" to Graph.settings.captureValidated.toString(),
            "locale" to ctx.resources.configuration.locales.toLanguageTags(),
        )
    }
}
