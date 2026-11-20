# PerfMon+

PerfMon+, Android için kayan bir performans göstergesidir. Oyun oynarken veya başka bir uygulamayı kullanırken kare hızını, CPU ve GPU kullanımını, belleği, sıcaklığı ve pil verilerini ekranda gösterir. Kullanılabilen ölçümler cihaza göre değişir.

## Kurulum

1. Android 7.0 veya üzeri bir cihaza [son sürüm APK’sını](https://github.com/JrSpatt/PerfMon-PLus/releases/latest) kurun.
2. PerfMon+’ı açıp **Monitörü başlat** düğmesine dokunun. İstendiğinde **Diğer uygulamaların üzerinde göster** iznini verin.
3. Uygulama FPS’ini ölçmek için [Shizuku](https://shizuku.rikka.app/) servisini başlatın ve PerfMon+’a erişim izni verin.


Doğrudan DUMP izni de desteklenir. ADB ile izin vermek için şu komutu çalıştırabilirsiniz.

```sh
adb shell pm grant com.spatt.perfmon android.permission.DUMP
```

## FPS kaynağı

| Kaynak | Göstergedeki etiket | Ölçüm |
| --- | --- | --- |
| Shizuku veya doğrudan DUMP | FPS | Öndeki uygulamanın görüntü katmanına ait kare zamanlarından hesaplanır. |
| Ekran paneli | FPS | Sürücünün sunduğu panel ölçümüdür. Oyun kare hızından farklı olabilir. |
| Ekran yenileme hızı | Hz | Uygulama ve panel ölçümü alınamadığında ekranın yenileme hızı gösterilir. |

Bir FPS ölçümünden sonra veri bir tur kesilirse gösterge `FPS --` yazar. Kesinti sürerse ekran yenileme hızını `Hz` olarak gösterir. `0 FPS`, daha önce kare sunan görünür içerik katmanının durağan olduğunu belirtir. Ölçülen hız sıfırdan büyük ve 1 FPS’in altındaysa değer `<1` olarak görünür.

**Cihaz uyumluluğu → FPS kaynağı** satırına dokunarak kullanılan kaynağı, panel veya Hz’e geçiş nedenini ve son başarılı uygulama ölçümünü görebilirsiniz. Son ölçümün yanında uygulamanın adı, kaynak ve üzerinden geçen süre yer alır.

## Gösterge ayarları

- **İzleme** bölümünden metrikleri seçin. Desteklenen cihazlarda CPU çekirdeklerini kümeler hâlinde gösterebilirsiniz. **Güç** pil akımını mA, **Güç (W)** gücü watt cinsinden ayrı satırlarda gösterir.
- **Pencere** bölümündeki yenileme aralığı tüm satırlara uygulanır. Yazı boyutunu ve arka plan saydamlığını buradan ayarlayabilirsiniz.
- **Konum** bölümünde dikey ve yatay yerleşimi ayrı kaydedebilirsiniz. Göstergeyi sürükleyip iğne simgesine dokunduğunuzda konumu sabitlenir ve dokunuşlar alttaki uygulamaya geçer.
- Monitörü uygulamadan, bildirimden veya Hızlı Ayarlar kutucuğundan durdurabilirsiniz.

## Derleme

JDK 17, Android SDK 34 ve Build Tools 34.0.0 gerekir. Windows’ta proje dizininden çalıştırın.

```bat
gradlew.bat :app:assembleDebug
```

Linux ve macOS’ta `./gradlew :app:assembleDebug` kullanın. APK, `app/build/outputs/apk/debug/` dizininde oluşturulur.

İmzalı sürümü Windows’ta kaynaktan derleyip kurmak için `PERFMON_KEYSTORE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` ve `KEY_PASSWORD` ortam değişkenlerini ayarlayıp `kurulum.cmd --build` çalıştırın. Mevcut kurulumu güncellerken aynı imzalama anahtarını kullanın.

## Lisans

[LibXZR/PerfMon-Plus](https://github.com/libxzr/PerfMon-Plus) projesinden türetilmiştir. [BSD 3-Clause](LICENSE)
