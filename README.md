# PerfMon+ (fork)

Android için kayan pencere (overlay) performans monitörü: CPU küme frekansı/yükü, GPU, sıcaklık, RAM, güç (W/mA), pil sıcaklığı ve FPS.

## Rootsuz FPS

Öndeki uygulamanın gerçek FPS'ini görmek için bir kez şu komut çalıştırılır (adb, Shizuku veya root):

```
pm grant xzr.perfmon android.permission.DUMP
```

İzin yoksa sırayla ekran paneli FPS'ine, o da okunamazsa ekranın Hz değerine düşer. Uygulamadaki "Cihaz uyumluluğu" kartı hangi kaynağın kullanıldığını gösterir.

## Derleme

GitHub Actions (`.github/workflows/build.yml`) her push'ta release APK üretir; çıktı "Actions → Artifacts" altındadır. Yerelde: `./gradlew assembleRelease`.

## Krediler

* Orijinal proje: **PerfMon+ — LibXZR** (https://github.com/libxzr/PerfMon-Plus), BSD 3-Clause, © 2019 LibXZR. Lisans metni `LICENSE` dosyasındadır ve korunmalıdır.
* Türkçeleştirme: @al1deburdanyak
