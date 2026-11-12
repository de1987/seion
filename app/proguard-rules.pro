# Media3, Room, WorkManager, DataStore and kotlinx.serialization ship their own consumer rules.
# The MusicBrainz response models are @Serializable: the plugin-generated serializers are kept by the library rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
