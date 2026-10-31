# kotlinx.serialization: mantém serializers gerados
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Ponte JavaScript do WebView do APL
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
