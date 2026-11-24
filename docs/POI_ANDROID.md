# Excel dependency

The Excel reader uses the JitPack Android fork documented at:

- https://jitpack.io/p/vince688/poi-android
- https://github.com/vince688/poi-android

Gradle dependency:

```kotlin
implementation("com.github.vince688:poi-android:3.17")
```

The project also declares `https://jitpack.io` in `settings.gradle.kts`. The reader uses Apache POI `WorkbookFactory` through Android's Storage Access Framework `Uri` and supports `.xlsx` and `.xls` input streams.
