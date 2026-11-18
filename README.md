# PerfMon+

Android için kayan performans göstergesi. CPU, GPU, bellek, sıcaklık, batarya ve kare hızı ölçümlerini cihazın desteklediği kaynaklardan okur.

## Kullanım

**Monitörü başlat** düğmesine dokunup diğer uygulamaların üzerinde gösterme iznini verin. Göstergeyi sürükleyebilir veya **Konum** bölümünden dikey ve yatay yerleşimi ayrı ayarlayabilirsiniz. Gösterge üzerindeki iğne konumu kilitler. Kilidi Konum bölümünden açabilirsiniz. Gösterge uygulamadan, bildirimden veya Hızlı Ayarlar kutucuğundan durdurulabilir.

Oyun FPS’i için Shizuku’yu başlatıp PerfMon+’a izin verin. Doğrudan DUMP izni olan kurulumlar da desteklenir. İsteğe bağlı ADB komutu:

```
adb shell pm grant com.spatt.perfmon android.permission.DUMP
```

Oyun ölçümü alınamazsa erişilebilen panel verisi kullanılır. İkisi de okunamazsa ekran yenileme hızı **Hz** olarak gösterilir. Son ölçümün kaynağı **Cihaz uyumluluğu** bölümündedir.

## Derleme ve dağıtım

JDK 17 gerekir. Windows’ta yerel test APK’sı için `gradlew.bat :app:assembleDebug` çalıştırın.

GitHub deposunda **Settings → Secrets and variables → Actions** altında `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` ve `KEY_PASSWORD` repository secret’larını tanımlayın. İlk değer keystore dosyasının tek satır Base64 karşılığıdır.

Push ve manuel Actions çalıştırmaları imzalı APK’yı **Artifacts → PerfMon-Plus** altında sunar. `v1.0` gibi bir etiket gönderildiğinde aynı çalıştırmanın doğrulanmış APK’sı GitHub Releases’a da yüklenir. İmzalama bilgileri eksikse release derlemesi hata verir.

## Katkılar

Orijinal proje LibXZR tarafından geliştirildi. [PerfMon+](https://github.com/libxzr/PerfMon-Plus) · [BSD 3-Clause](LICENSE)

Geliştirme ve Türkçeleştirme [@al1deburdanyak](https://t.me/al1deburdanyak).
