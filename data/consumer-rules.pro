# kotlinx.serialization keeps the generated serializers referenced reflectively.
-keepclassmembers class de.universam.victron.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class de.universam.victron.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The GitHub release JSON is deserialized the same way, and it lives in update/, not model/ —
# without these the self updater fails only in a minified release build, which is exactly the
# build that ships.
-keepclassmembers class de.universam.victron.data.update.** {
    *** Companion;
}
-keepclasseswithmembers class de.universam.victron.data.update.** {
    kotlinx.serialization.KSerializer serializer(...);
}
