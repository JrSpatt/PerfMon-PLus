# PerfMon+

## 2.1

- FPS ölçümü, odaktaki uygulamanın ana penceresini ve görünür içerik katmanlarını izler. Chrome ve oyunlarda kareler başka bir katmana geçtiğinde seçim yenilenir. Durağanlık doğrulandığında `0 FPS`, ölçülen hız saniyede bir karenin altındaysa `<1` gösterilir.
- Uygulama ve panel FPS ölçümü bir tur kesildiğinde göstergede `FPS --` görünür. Kesinti sürerse ekranın yenileme hızı gösterilir.
- Sabitlenmiş göstergede dört haneli CPU frekansına yer ayrıldı. `999 MHz` ile `1000 MHz` arasındaki geçişlerde pencere genişliği sabit kalır.
- İzleme bölümünde CPU, Kümelere ayır, Güç (mA) ve Güç (W) seçenekleri ortak iki sütunlu düzende yer alır.

## 2.0

- Shizuku ve DUMP ile FPS ölçümü hem düz katman adlarını hem de Android 16’daki `RequestedLayerState` biçimini destekler.
- FPS ölçümünde odaktaki pencere ve uygulama birlikte değerlendirilir. Birden fazla yüzey ayırt edilemezse gösterge panel veya Hz kaynağına geçer.
- **Cihaz uyumluluğu → FPS kaynağı** kullanılan kaynağı, geçiş nedenini ve son başarılı uygulama ölçümünün adını, kaynağını ve üzerinden geçen süreyi gösterir.
- **Güç** (mA) ve **Güç (W)** bağımsız olarak açılır. İkisi seçildiğinde göstergede alt alta yer alır. Güncellemede eski Güç tercihi her iki satıra aktarılır.
- **Kümelere ayır** seçeneği CPU ölçümlerinin altına taşındı. Küme bilgisi eksikse çekirdekler numaralarıyla gösterilir.
- Okunamayan sensörlerde `--` gösterilir. Geçerli sıfır değerleri korunur. İçerik kısaldığında gösterge yeniden daralır.
- CPU sıcaklığına donanım eşikleri dahil edilmez. GPU kaynakları ve birimleri birlikte güncellenir.
- Shizuku bağlantı denemesi zaman aşımına uğrarsa veya servis koparsa monitör açıkken bağlantı yeniden denenir.
- Başlatma ekranı gösterge açılınca arka plana geçer. Pencere açılamazsa uygulama hatayı bildirir.

## 1.0

- Uygulamanın paket adı `com.spatt.perfmon` oldu. Sürüm kodu 1.
- Göstergedeki sabitleme düğmesi, simgeleri gizler ve oyun dokunuşlarının geçmesini sağlar. Kilit Konum bölümünden açılır.
- Dikey ve yatay konumlar ayrı kaydedilir. Konum şeması iki yönde de düzenlenebilir.
- Canlı veri önizlemesi kaldırıldı. Ölçüm yalnızca gösterge çalışırken yapılır.
- Yazı boyutu ve saydamlık göstergeye sürükleme sırasında uygulanır. Kenar ve sütun boşlukları yazı boyutuyla ölçeklenir.
- İzleme, Pencere ve Gelişmiş bölümleri bağımsız açılır ve seçimlerini hatırlar.
- Uygulamadaki Başlat/Durdur düğmesi ve çalışma durumu gösterge, bildirim ve Hızlı Ayarlar ile eşzamanlı güncellenir.
- FPS kaynakları sırasıyla Shizuku, doğrudan DUMP, panel ve ekran yenileme hızıdır. Panel ve oyun ölçümleri göstergede FPS, yenileme hızı Hz olarak görünür. Kullanılan kaynak Cihaz uyumluluğu bölümünde gösterilir.
- Hakkında bölümüne geliştiricinin Telegram bağlantısı eklendi. Orijinal proje ve lisans bilgileri korundu.
- GitHub Actions, APK’yı deponun imzalama anahtarıyla imzalar ve doğrular. Sürüm etiketi gönderildiğinde APK GitHub Releases’a eklenir.
