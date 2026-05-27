# ============================================================
# BuildBattleAI - Heavy obfuscation
# Activate: mvn clean package -Pobfuscate-heavy
#
# Maximum name obfuscation: repackaging + aggressive method
# overloading + access modification. The decompiled output
# is as unreadable as possible without shrinking/optimization.
# ============================================================

-dontshrink
-dontoptimize

# Repackage all renamed classes into one flat package
-repackageclasses 'ru.ashesha.buildBattleAI.internal'

# Allow methods with different return types to share names —
# makes decompiled code extremely confusing
-overloadaggressively

# Widen access modifiers (private → package/public) so ProGuard
# can assign shorter, more overlapping names across class boundaries
-allowaccessmodification
