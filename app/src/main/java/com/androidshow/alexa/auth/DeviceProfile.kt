package com.androidshow.alexa.auth

import android.os.Build
import com.androidshow.alexa.BuildConfig

/**
 * Identidade com que o celular se registra na Amazon. Os campos vêm da configuração de quem compila
 * (local.properties ou variáveis de ambiente, ver README); sem tipo de dispositivo o login fica bloqueado.
 * Os demais campos, se vazios, descrevem o próprio AndroidShow.
 */
object DeviceProfile {
    val DEVICE_TYPE: String = BuildConfig.PROFILE_DEVICE_TYPE
    val isConfigured: Boolean get() = DEVICE_TYPE.isNotEmpty()
    val APP_NAME: String get() = BuildConfig.PROFILE_APP_NAME.ifEmpty { "AndroidShow" }
    val APP_VERSION: String get() = BuildConfig.PROFILE_APP_VERSION.ifEmpty { BuildConfig.VERSION_NAME }
    val DEVICE_MODEL: String get() = BuildConfig.PROFILE_DEVICE_MODEL.ifEmpty { Build.MODEL }
    val OS_NAME: String get() = BuildConfig.PROFILE_OS_NAME.ifEmpty { "Android" }
    val OS_VERSION: String get() = BuildConfig.PROFILE_OS_VERSION.ifEmpty { Build.VERSION.RELEASE }
    const val SOFTWARE_VERSION = "1"                  // [Inferido]
    const val SDK_VERSION = "6.12.4"                  // [Inferido] versão MAP usada por clientes conhecidos

    /**
     * Nome exibido no app Alexa. Precisa ser único na conta: em alguns tipos de dispositivo a Amazon
     * não expande `%DUPE_STRATEGY_1ST%` e responde DuplicateDeviceName [Observado].
     */
    fun deviceName(model: String, serial: String) = "AndroidShow $model ${serial.takeLast(4)}"
}
