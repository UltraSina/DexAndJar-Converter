# =========================================================
# DEX & JAR CONVERTER PROGUARD RULES
# =========================================================

# Preserve line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep attributes necessary for standard Java Reflection
-keepattributes Signature,Exceptions,*Annotation*,InnerClasses

# ── KEEP ENGINE 1 & 2: Dex2Jar ───────────────────────────
# Protects the Dex2Jar translation pipelines from being stripped
-keep class com.googlecode.d2j.** { *; }
-keep class com.googlecode.dex2jar.** { *; }
-keep class de.femtopedia.dex2jar.** { *; }

# ── KEEP ENGINE 3: Google R8 / D8 ────────────────────────
# Protects the D8 Compiler Engine
-keep class com.android.tools.r8.** { *; }

# ── KEEP ENGINE 4 & 5: Smali & Baksmali ──────────────────
# Smali and Baksmali use the 'org.jf' package name internally (Dexlib2)
# We are calling their 'Main' methods via Reflection, so they MUST NOT be renamed.
-keep class org.jf.** { *; }
-keep class org.smali.** { *; }

# ── JCOMMANDER FALLBACK ──────────────────────────────────
# Dex2Jar heavily relies on JCommander for CLI argument parsing
-keep class com.beust.jcommander.** { *; }

# ── IGNORE MISSING DESKTOP JAVA CLASSES ──────────────────
# These desktop JVM classes are referenced by R8/Dex2Jar/Smali 
# but do not exist on Android. We tell the minifier to ignore them.

-dontwarn com.sun.management.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.xml.stream.**

# (Optional but recommended) Ignore other common desktop APIs often found in these libraries
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn sun.misc.**
