# Gson lee/escribe estos modelos por reflexión: sus campos forman parte del formato
# (JSON de los registros TXT y datos guardados por versiones anteriores).
-keep class com.mivisor.app.model.StreamItem { *; }
-keep class com.mivisor.app.model.HistoryItem { *; }
-keep class com.mivisor.app.model.SourceProfile { *; }
# Historial de reproducciones (v11): se guarda con Gson.
-keep class com.mivisor.app.model.PlaybackEntry { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Logs fuera en release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Ace Stream Engine (AIDL oficial): los descriptores del Binder deben coincidir con el servicio de Ace Stream.
-keep class org.acestream.engine.service.v0.** { *; }
-keep class org.acestream.engine.ServiceClient { *; }
-keep interface org.acestream.engine.ServiceClient$Callback { *; }

# Motor P2P interno (v15): metadatos de fuentes que se guardan con Gson (filesDir/engine/sources.json).
-keep class com.mivisor.app.engine.SourceMemory$ContentInfo { *; }
# Salud de fuentes por Content ID (filesDir/engine/health.json), con Gson.
-keep class com.mivisor.app.engine.SourceHealth$Record { *; }
-keep enum com.mivisor.app.engine.HealthState { *; }
