# LogFlow v5 — Windows kullanım kılavuzu

JDK 17 veya üzeri kurulu olmalıdır. ZIP içindeki logflow klasörünü VS Code ile açın ve terminalde bu klasörde olduğunuzu kontrol edin.

```powershell
java -version
javac -version
.\logflow.bat --config configs\chaos.yaml
```

Kaos örneği 28 kayıt işler: 10 geçerli kayıt ve 18 hatalı kayıt. Sonuçları görüntülemek için:

```powershell
Get-Content .\out\chaos-clean.txt
Get-Content .\out\dead-letter.jsonl
Get-Content .\out\chaos-summary.json
```

Testler Maven kuruluysa `mvn test` ile çalıştırılabilir. Windows derlemesinde UTF-8 kullanılır. Proje ve hata politikalarının ayrıntıları README.md ve ARCHITECTURE.md dosyalarındadır.

## Gönderilen ek bozuk veri dosyası

Gönderdiğiniz ZIP, `data/fixture/` klasöründe korunmuştur. Çalıştırmak için:

```powershell
.\logflow.bat --config configs\fixture-chaos.yaml
Get-Content .\out\fixture-clean.txt
Get-Content .\out\fixture-dead-letter.jsonl
Get-Content .\out\fixture-summary.json
```

Sonuç: 11 satır = 3 geçerli kayıt + 8 dead-letter. İlk satır log değil bir açıklama olduğu için dead-letter kanalına gider. Boş dosya `data/fixture/access-empty.log` otomatik testte ayrıca kontrol edilir. Mevcut 28 satırlık kaos örneği ve `configs/chaos.yaml` korunmuştur.
