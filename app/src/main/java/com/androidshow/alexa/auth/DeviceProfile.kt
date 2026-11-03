package com.androidshow.alexa.auth

import com.androidshow.alexa.BuildConfig

/**
 * Identidade com que o celular se registra na Amazon.
 * Imita o app Alexa para Windows (pacote 57540AMZNMobileLLC.AmazonAlexa 3.25.1177.0), que é o
 * cliente que a nuvem trata como tela compatível com o modo Show.
 * Campos marcados [Inferido] ainda não foram confirmados no Alexa.dll (ver docs/research).
 */
object DeviceProfile {
    /** Definido por quem compila (local.properties ou variável de ambiente); vazio = login bloqueado. */
    val DEVICE_TYPE: String = BuildConfig.DEVICE_TYPE
    val isConfigured: Boolean get() = DEVICE_TYPE.isNotEmpty()
    const val APP_NAME = "Amazon Alexa"               // [Inferido]
    const val APP_VERSION = "3.25.1177.0"             // [Observado] versão do pacote
    const val DEVICE_MODEL = "Windows"                // [Inferido]
    const val OS_NAME = "Windows"                     // [Inferido]
    const val OS_VERSION = "10.0.26100"               // [Inferido]
    const val SOFTWARE_VERSION = "1"                  // [Inferido]
    const val SDK_VERSION = "6.12.4"                  // [Inferido] versão MAP usada por clientes conhecidos

    /**
     * Nome exibido no app Alexa. Precisa ser único na conta: para este tipo de dispositivo a Amazon
     * não expande `%DUPE_STRATEGY_1ST%` e responde DuplicateDeviceName [Observado].
     */
    fun deviceName(model: String, serial: String) = "AndroidShow $model ${serial.takeLast(4)}"
}
