# ============================================================
# BuildBattleAI - Standard obfuscation
# Activate: mvn clean package -Pobfuscate
#
# Renames + repackages all obfuscated classes into a single
# flat package, completely hiding the original package structure.
# More aggressive than light — harder to reconstruct architecture.
# ============================================================

-dontshrink
-dontoptimize

# Repackage all renamed classes into one flat package —
# eliminates original package structure from decompiled output
-repackageclasses 'ru.ashesha.buildBattleAI.internal'
