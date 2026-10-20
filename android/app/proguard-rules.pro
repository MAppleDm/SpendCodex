# kotlinx.serialization, Room, Hilt and OkHttp ship their own consumer rules.
#
# The export format is read and written by generated serializers; keep the format classes whole so a release build
# writes exactly the same field names as a debug build (the names are the file format).
-keep class dev.spendcodex.data.backup.** { *; }

# Stack traces in bug reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
