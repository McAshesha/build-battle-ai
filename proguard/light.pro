# ============================================================
# BuildBattleAI - Light obfuscation
# Activate: mvn clean package -Pobfuscate-light
#
# Renames internal classes, methods, and fields to short names.
# Does NOT remove unused code or apply bytecode optimizations.
# Safest level - only hides names.
# ============================================================

-dontshrink
-dontoptimize

# Move renamed classes into a flat package to hide structure
-flattenpackagehierarchy 'ru.ashesha.buildBattleAI.internal'
