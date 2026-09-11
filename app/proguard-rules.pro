# adhan is plain Java with no reflection; default rules suffice.
# kotlinx.serialization keeps generated serializers referenced from @Serializable classes.
-keepclassmembers class com.asimzf.salaahalarm.** {
    *** Companion;
}
-keepclasseswithmembers class com.asimzf.salaahalarm.** {
    kotlinx.serialization.KSerializer serializer(...);
}
