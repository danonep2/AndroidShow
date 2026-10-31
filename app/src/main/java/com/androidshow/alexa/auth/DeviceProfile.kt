package com.androidshow.alexa.auth

/**
 * Identidade com que o celular se registra na Amazon.
 * Imita o app Alexa para Windows (pacote 57540AMZNMobileLLC.AmazonAlexa 3.25.1177.0), que é o
 * cliente que a nuvem trata como tela compatível com o modo Show.
 * Campos marcados [Inferido] ainda não foram confirmados no Alexa.dll (ver docs/research).
 */
object DeviceProfile {
    const val DEVICE_TYPE = "<REMOVIDO>"          // [Observado] Config do app Windows
    const val APP_NAME = "Amazon Alexa"               // [Inferido]
    const val APP_VERSION = "3.25.1177.0"             // [Observado] versão do pacote
    const val DEVICE_MODEL = "Windows"                // [Inferido]
    const val OS_NAME = "Windows"                     // [Inferido]
    const val OS_VERSION = "10.0.26100"               // [Inferido]
    const val SOFTWARE_VERSION = "1"                  // [Inferido]
    const val SDK_VERSION = "6.12.4"                  // [Inferido] versão MAP usada por clientes conhecidos
    const val DEVICE_NAME = "%FIRST_NAME%'s%DUPE_STRATEGY_1ST%AndroidShow"
}
