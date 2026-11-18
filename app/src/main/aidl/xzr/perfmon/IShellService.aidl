package xzr.perfmon;

interface IShellService {
    // Shizuku sunucusunun tanımladığı yok etme metodu
    void destroy() = 16777114;

    // Komutu shell yetkisiyle çalıştırır, çıktıyı satır satır döndürür
    String exec(in String[] cmd, int maxLines, int timeoutMs) = 1;
}
