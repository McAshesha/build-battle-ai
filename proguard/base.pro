# ============================================================
# BuildBattleAI - ProGuard base configuration
# Shared keep rules used by all obfuscation levels.
# ============================================================

# -- Target language level ----------------------------------------
-target 1.8

# Skip preverification — Java 8 bytecode from javac already has
# valid stack map frames. ProGuard's preverifier requires complete
# class hierarchies including optional deps (ViaVersion, etc.)
# that are only present at runtime on some servers.
-dontpreverify

# -- Preserve bytecode attributes ---------------------------------
# Signature:           Gson TypeToken generic resolution (LocalRepository)
# InnerClasses:        correct inner class access patterns
# EnclosingMethod:     anonymous classes (DataService auto-save runnable)
# *Annotation*:        Bukkit @EventHandler dispatch, Lombok runtime markers
# SourceFile/LineNumber: readable stack traces via mapping file
# Exceptions:          exception metadata for correct catch-block analysis
-keepattributes Signature,
                InnerClasses,
                EnclosingMethod,
                *Annotation*,
                SourceFile,
                LineNumberTable,
                Exceptions

# -- Suppress warnings for missing classes -------------------------
# JDK classes: jmod files use class version 69 (JDK 25) which
# ProGuard 7.6 cannot read, so no JDK library is provided.
# Obfuscation works correctly without resolving JDK hierarchies.
-dontwarn java.**
-dontwarn javax.**
-dontwarn sun.**
-dontwarn jdk.**
-dontwarn com.sun.**

# Spigot/Bukkit/NMS: provided at runtime, not in the shaded JAR.
-dontwarn org.bukkit.**
-dontwarn org.spigotmc.**
-dontwarn net.minecraft.**
-dontwarn io.netty.**
-dontwarn com.mojang.**
-dontwarn com.google.gson.**
-dontwarn com.google.common.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.intellij.lang.annotations.**

# Lombok: compile-time only, annotations in bytecode but classes not in JAR.
-dontwarn lombok.**

# ViaVersion: optional runtime dep of PacketEvents, not shipped in JAR.
-dontwarn com.viaversion.**

# JSpecify @Nullable / @NonNull / @NullMarked: compile-time annotation API
# used heavily by PacketEvents; classes are not on the runtime classpath
# and not needed for execution.
-dontwarn org.jspecify.**

# Checker Framework annotations: compile-time only.
-dontwarn org.checkerframework.**

# Google AutoService: compile-time SPI generator, no runtime presence.
-dontwarn com.google.auto.service.**

# ASM bytecode library: used by XSeries XReflectASM only when reflective
# rewriting is requested. Plugin works fine without it on the classpath.
# The two `XReflectASM`/`MethodRewriter` field-type warnings also stem
# from ASM not being on the classpath at obfuscation time.
-dontwarn org.objectweb.asm.**
-dontwarn ru.ashesha.buildBattleAI.libs.xseries.reflection.asm.**

# Kyori MiniMessage: optional Adventure module (we only shade
# adventure-text-serializer-legacy). XSeries AdventureAPIFactory probes
# for it at runtime via reflection; absence is the expected fallback.
-dontwarn ru.ashesha.buildBattleAI.libs.kyori.adventure.text.minimessage.**
-dontwarn ru.ashesha.buildBattleAI.libs.xseries.paper.AdventureAPIFactory

# Folia / Paper-specific schedulers and events: PacketEvents detects
# their presence at runtime via reflection and falls back to Bukkit APIs.
-dontwarn io.papermc.paper.threadedregions.**
-dontwarn io.papermc.paper.event.**
-dontwarn io.papermc.paper.connection.**

# BungeeCord chat components: provided by Spigot/Paper at runtime as part
# of the chat-component API used by XSeries Title/ActionBar.
-dontwarn net.md_5.bungee.**

# Log4j: provided by the server runtime.
-dontwarn org.apache.logging.log4j.**

# Safety net: the shaded JAR references many provided-scope classes
# (Bukkit, Spigot, NMS) that only exist at runtime. Proceed despite
# any remaining unresolved references.
-ignorewarnings

# Suppress duplicate class notes from shade merging
-dontnote **

# =================================================================
# KEEP RULES
# =================================================================

# -- 1. Main plugin class -----------------------------------------
# Bukkit loads this by name from plugin.yml via Class.forName().
# All public methods (onLoad, onEnable, onDisable, getContext, etc.)
# and fields must retain their names.
-keep public class ru.ashesha.buildBattleAI.BuildBattleAI {
    public <methods>;
    public <fields>;
}

# -- 2. All API subpackages ---------------------------------------
# 10 service interfaces + 2 DTOs. Public contract for external code,
# Gson reflection, and Ignite serialization.
-keep class ru.ashesha.buildBattleAI.**.api.** { *; }

# -- 3. Serializable DTOs (Ignite + Gson) -------------------------
# PlayerData / ArenaStats field names are used by Gson.fromJson()
# (reflection on field names) and Ignite binary serialization.
# Already covered by rule 2 (they live in data.api), but explicit
# for safety and documentation.
-keepclassmembers class ru.ashesha.buildBattleAI.data.api.PlayerData {
    <fields>;
}
-keepclassmembers class ru.ashesha.buildBattleAI.data.api.ArenaStats {
    <fields>;
}

# Standard Serializable contract
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# -- 4. Core lifecycle classes ------------------------------------
# PluginContext: accessed via BuildBattleAI.getContext(), exposes
# all service getters. PluginService: lifecycle interface that all
# services implement (enable/shutdown/reload).
-keep class ru.ashesha.buildBattleAI.core.PluginContext { *; }
-keep class ru.ashesha.buildBattleAI.core.PluginService { *; }

# -- 5. Utility classes referenced by name / security-critical ----
-keep class ru.ashesha.buildBattleAI.util.ReflectionUtils { *; }
-keep class ru.ashesha.buildBattleAI.util.JarIntegrityVerifier { *; }

# -- 6. Shaded / relocated third-party libraries -----------------
# XSeries, PacketEvents, Kyori use internal reflection, ServiceLoader,
# and annotation processing. Must not be touched.
-keep class ru.ashesha.buildBattleAI.libs.** { *; }

# Ignite uses ServiceLoader, JMX, custom class loading, and
# reflection extensively. Not relocated due to these constraints.
-keep class org.apache.ignite.** { *; }
-keep class javax.cache.** { *; }

# ONNX Runtime: native bindings use JNI with hard-coded class names
# (e.g. ai/onnxruntime/OnnxTensor). Renaming or removing any class /
# method breaks the native library lookup. Not relocated.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# -- 7. Bukkit @EventHandler methods ------------------------------
# Bukkit dispatches events via reflection to methods annotated
# with @EventHandler. Renaming breaks handler dispatch.
-keepclassmembers class * {
    @org.bukkit.event.EventHandler <methods>;
}

# -- 8. Bukkit Listener / Command / PacketListener hierarchies ----
# Bukkit discovers Listeners and Commands via type hierarchy.
# PacketEvents discovers PacketListeners the same way.
-keep class * implements org.bukkit.event.Listener { *; }
-keep class * extends org.bukkit.command.Command { *; }
-keep class * implements com.github.retrooper.packetevents.event.PacketListener { *; }

# Service base types for commands and listeners
-keep class ru.ashesha.buildBattleAI.commands.CommandService { *; }
-keep class ru.ashesha.buildBattleAI.commands.CommandService$PluginCommand { *; }
-keep class ru.ashesha.buildBattleAI.listeners.ListenerService { *; }
-keep class ru.ashesha.buildBattleAI.listeners.ListenerService$PluginListener { *; }

# -- 9. Enum classes ----------------------------------------------
# JVM requires values() and valueOf() via reflection.
# BlockPalette uses XMaterial.ordinal() as array index for COLORS,
# ALPHAS, EMISSIVE, BLOCK_FLAGS — removing "unused" enum constants
# shifts ordinals and breaks the parallel arrays.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# -- 10. Native methods (safety net) ------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# -- 11. BukkitRunnable subclasses --------------------------------
# Bukkit scheduler accesses these via type hierarchy.
-keep class * extends org.bukkit.scheduler.BukkitRunnable
