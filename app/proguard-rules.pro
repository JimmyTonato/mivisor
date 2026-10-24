# Gson lee/escribe estos modelos por reflexión: sus campos forman parte del formato
# (JSON de los registros TXT y datos guardados por versiones anteriores).
-keep class com.mivisor.app.model.StreamItem { *; }
-keep class com.mivisor.app.model.HistoryItem { *; }
-keep class com.mivisor.app.model.SourceProfile { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Logs fuera en release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
