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
