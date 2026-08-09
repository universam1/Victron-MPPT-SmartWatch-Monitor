# kotlinx.serialization keeps the generated serializers referenced reflectively.
-keepclassmembers class de.universam.victron.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class de.universam.victron.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
