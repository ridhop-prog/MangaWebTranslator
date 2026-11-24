# ProGuard / R8 rules for ScreenMangaTranslator
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep our app entry points & views
-keep class com.pribadi.mangatranslator.MainActivity { *; }
-keep class com.pribadi.mangatranslator.FloatingService { *; }
-keep class com.pribadi.mangatranslator.OverlayView { *; }
-keep class com.pribadi.mangatranslator.TranslatedBlock { *; }

# Keep ML Kit & Google Play Services components safely
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn javax.annotation.**
