# حركة مستودع المندوبين

تطبيق Android Native مبني بـ Kotlin وJetpack Compose لإدارة حركة مستودع المندوبين.

المشروع يستخدم JDK 17 وAndroid Gradle Plugin 8.7.2 وGradle 8.9 وKotlin 1.9.24، مع compileSdk وtargetSdk 35 وminSdk 26.

لا يستخدم المشروع Expo أو React Native أو EAS. البناء الرسمي يتم عبر GitHub Actions، ويُنشأ Release عند دفع tag يبدأ بـ `v` مثل `v0.1.0`. يقوم workflow بإنشاء APK Release موقّع ورفعه إلى GitHub Release.

للبناء محليًا عند توفر Android SDK:

```bash
./gradlew :app:assembleRelease --no-daemon --stacktrace
```
