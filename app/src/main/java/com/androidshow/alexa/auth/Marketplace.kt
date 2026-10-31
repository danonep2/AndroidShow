package com.androidshow.alexa.auth

/** Região do AVS: define o gateway de voz e a API da Alexa (Config.xml do app Windows). */
enum class AvsRegion(val gateway: String, val alexaApi: String, val identityHost: String) {
    NA("alexa.na.gateway.devices.a2z.com", "api.amazonalexa.com", "api.amazon.com"),
    EU("alexa.eu.gateway.devices.a2z.com", "api.eu.amazonalexa.com", "api.amazon.co.uk"),
    FE("alexa.fe.gateway.devices.a2z.com", "api.fe.amazonalexa.com", "api.amazon.co.jp"),
}

/** Loja Amazon da conta. Define domínio de login, de registro e o site da Alexa. */
enum class Marketplace(val label: String, val domain: String, val region: AvsRegion, val language: String) {
    BR("Brasil", "amazon.com.br", AvsRegion.NA, "pt_BR"),
    US("Estados Unidos", "amazon.com", AvsRegion.NA, "en_US"),
    CA("Canadá", "amazon.ca", AvsRegion.NA, "en_CA"),
    MX("México", "amazon.com.mx", AvsRegion.NA, "es_MX"),
    UK("Reino Unido", "amazon.co.uk", AvsRegion.EU, "en_GB"),
    DE("Alemanha", "amazon.de", AvsRegion.EU, "de_DE"),
    FR("França", "amazon.fr", AvsRegion.EU, "fr_FR"),
    ES("Espanha", "amazon.es", AvsRegion.EU, "es_ES"),
    IT("Itália", "amazon.it", AvsRegion.EU, "it_IT"),
    IN("Índia", "amazon.in", AvsRegion.EU, "en_IN"),
    JP("Japão", "amazon.co.jp", AvsRegion.FE, "ja_JP"),
    AU("Austrália", "amazon.com.au", AvsRegion.FE, "en_AU");

    /** Handle OpenID do login: amazon.com usa o base, as demais lojas o sufixo do TLD. */
    val assocHandle: String
        get() = if (domain == "amazon.com") ASSOC_HANDLE else "${ASSOC_HANDLE}_${domain.substringAfterLast('.')}"

    val wwwHost get() = "www.$domain"
    /** Serviço de identidade (MAP/"panda") da região. [Observado] o Windows registra em api.amazon.com. */
    val apiHost get() = region.identityHost
    val alexaWebHost get() = "alexa.$domain"
    val locale get() = language.replace('_', '-')

    companion object {
        // [Inferido] Valor do app Alexa iOS, usado por alexa-cookie/alexapy; trocar pelo do app Windows quando confirmado.
        const val ASSOC_HANDLE = "amzn_dp_project_dee_ios"
    }
}
