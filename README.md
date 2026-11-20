# PerfMon+

[English](README.en.md)

PerfMon+, Android için kayan bir performans göstergesidir. Oyun oynarken veya başka bir uygulamayı kullanırken FPS, CPU ve GPU değerleri, RAM kullanımı, sıcaklık ve pil verilerini gösterir. Kullanılabilen ölçümler cihaza göre değişir.

## Kurulum

1. `PerfMon-Plus.apk` dosyasını indirip Android 7.0 veya üzeri cihazınıza kurun. [Son sürüm](https://github.com/JrSpatt/PerfMon-PLus/releases/latest)
2. PerfMon+’ı açıp **Monitörü başlat** düğmesine dokunun. İstendiğinde uygulamaya diğer uygulamaların üzerinde gösterme izni verin.

Öndeki uygulamanın FPS’ini ölçmek için Shizuku’yu başlatıp PerfMon+’a erişim izni verebilirsiniz. [Shizuku](https://shizuku.rikka.app/)

Cihazınız destekliyorsa aynı ölçüm için ADB ile `DUMP` izni de verebilirsiniz.

```sh
adb shell pm grant com.spatt.perfmon android.permission.DUMP
```

## FPS ölçümü

| Kaynak | Etiket | Gösterilen değer |
| --- | --- | --- |
| Shizuku veya DUMP izni | `FPS` | Öndeki uygulamanın kare sunum zamanlarından hesaplanan hız |
| Panel sürücüsü | `FPS` | Sürücünün bildirdiği panel kare hızı |
| Ekran | `Hz` | Uygulama ve panel ölçümü alınamadığında ekranın yenileme hızı |

FPS verisi bir tur kesilirse gösterge `FPS --` yazar. Kesinti sürerse `Hz` değerine geçer. Uygulama ölçümünde `0 FPS` görünür içeriğin durağan olduğunu, `<1` ise saniyede bir kareden az ölçüldüğünü belirtir.

**Cihaz uyumluluğu → FPS kaynağı** bölümünde kullanılan kaynağı, panel veya Hz’e geçiş nedenini ve son başarılı uygulama ölçümünü görebilirsiniz.

## Gösterge ayarları

- **İzleme** bölümünden göstergede yer alacak değerleri seçin. CPU çekirdeklerini kümelere ayırabilir, pil akımını (**Güç**, mA) ve pil gücünü (**Güç (W)**) ayrı ayrı açabilirsiniz.
- **Pencere** bölümünden yazı boyutunu, saydamlığı ve tüm satırların yenileme aralığını ayarlayın.
- **Konum** bölümünde dikey ve yatay ekran için ayrı konum kaydedebilirsiniz. Göstergedeki iğneye dokunduğunuzda konum sabitlenir ve dokunuşlar alttaki uygulamaya geçer.
- Göstergeyi uygulamadan, bildirimden veya Hızlı Ayarlar kutucuğundan durdurabilirsiniz.

## Derleme

JDK 17, Android SDK Platform 34 ve Build Tools 34.0.0 gerekir. Proje klasöründe işletim sisteminize uygun komutu çalıştırın.

| Sistem | Komut |
| --- | --- |
| Windows (CMD) | `gradlew.bat :app:assembleDebug` |
| Linux / macOS | `./gradlew :app:assembleDebug` |

APK, `app/build/outputs/apk/debug/` klasörüne yazılır.

## Lisans

LibXZR’nin PerfMon+ projesinden türetilmiştir. [Orijinal proje](https://github.com/libxzr/PerfMon-Plus) · [BSD 3-Clause lisansı](LICENSE)
