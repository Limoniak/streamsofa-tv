# JNA résout les structures, callbacks et méthodes natives par réflexion.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
# Bindings UniFFI générés : leurs noms doivent correspondre aux symboles du .so.
-keep class dev.lumen.core.** { *; }
