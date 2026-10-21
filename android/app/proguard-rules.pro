# WorkManager builds its Room database by reflection (WorkDatabase_Impl()); without this
# R8 drops the no-arg constructor and the app crashes on launch in InitializationProvider.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
