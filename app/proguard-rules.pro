# ProGuard / R8 rules for ScreenMangaTranslator
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,JavascriptInterface

# Keep our app entry points, views, and WebView JavascriptInterface bridge
-keep class com.pribadi.mangatranslator.MainActivity { *; }
-keep class com.pribadi.mangatranslator.WebComicActivity { *; }
-keep class com.pribadi.mangatranslator.WebComicActivity$JsPanelBridge { *; }
-keep class com.pribadi.mangatranslator.FloatingService { *; }
-keep class com.pribadi.mangatranslator.OverlayView { *; }
-keep class com.pribadi.mangatranslator.TranslatedBlock { *; }

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep ML Kit & Google Play Services components safely
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn javax.annotation.**
