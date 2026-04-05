package com.uzaktanbildirim.mobile

import android.text.Spanned
import androidx.core.text.HtmlCompat

object GettingStartedGuide {
    fun buildGuideText(): Spanned {
        val html = """
            <b>Uzaktan Bildirim Ilk Kurulum Rehberi</b><br/>
            Bu rehber tek parca bir kurulum akisi verir. Hedef su: masaustunde bir kurulum klasoru hazirla, kendi Cloudflare Worker'ini kur, kendi Firebase projenle APK build al, sonra Windows ajan ve telefonu ayni Worker URL ile eslestir.<br/><br/>

            <b>1. Sistem ne yapiyor?</b><br/>
            • Mimari su sekildedir: <b>Windows ajan -> Cloudflare Worker -> Firebase Cloud Messaging -> Android uygulama</b>.<br/>
            • Windows ajani bilgisayarda acik kalir, Worker ile haberlesir ve gelen komutlari uygular.<br/>
            • Android uygulama ayni Worker uzerinden PC durumunu gorur, bildirim alir ve uzaktan komut gonderir.<br/>
            • Buyuk dosya aktarimlari icin <b>R2</b>, durum ve cihaz kayitlari icin <b>D1</b> kullanilabilir.<br/><br/>

            <b>2. Ana klasoru hazirla</b><br/>
            1. Masaustunde <b>UzaktanBildirimKurulum</b> adli bir klasor olustur.<br/>
            2. Bunun icinde <b>exe</b>, <b>apk</b> ve <b>backend</b> adli uc klasor ac.<br/>
            3. exe klasorune Windows ajan dosyalarini koy.<br/>
            4. apk klasorune Android proje dosyalarini koy.<br/>
            5. backend klasorune temiz Worker template dosyalarini koy.<br/>
            6. Ana klasorde <tt>backend-kurulum-yardimcisi.bat</tt>, <tt>apk-build-yardimcisi.bat</tt>, <tt>d1-tablolari-onar-yardimcisi.bat</tt> ve <tt>r2-sonradan-ekle-yardimcisi.bat</tt> gibi yardimci dosyalar da bulunur.<br/><br/>

            <b>3. exe klasorunde neler olacak?</b><br/>
            • <b>PcRemoteAgent.exe</b><br/>
            • <b>PcRemoteAgent.dll</b><br/>
            • <b>PcRemoteAgent.deps.json</b><br/>
            • <b>PcRemoteAgent.runtimeconfig.json</b><br/>
            • Bu dort dosya ayni klasorde kalmalidir.<br/><br/>

            <b>4. apk klasorunde neler olacak?</b><br/>
            • Android proje dosyalari burada olur.<br/>
            • Bu modelde kendi Firebase projenle yeni bir APK build alacaksin.<br/>
            • Ana klasorde bulunan <tt>apk-build-yardimcisi.bat</tt> dosyasi bunu kolaylastirir.<br/><br/>

            <b>5. backend klasorunde neler olacak?</b><br/>
            • Temiz Cloudflare Worker template dosyalari burada olur.<br/>
            • Genelde <tt>src</tt>, <tt>migrations</tt>, <tt>package.json</tt>, <tt>package-lock.json</tt>, <tt>tsconfig.json</tt>, <tt>wrangler.template.jsonc</tt> ve <tt>.dev.vars.example</tt> gibi dosyalar bulunur.<br/>
            • R2 destekli kurulum icin <tt>wrangler.template.r2.jsonc</tt> de bulunur.<br/>
            • Bu klasorde sana ait canli Worker URL, token, secret veya database id bulunmamalidir.<br/><br/>

            <b>6. Cloudflare hesabini hazirla</b><br/>
            1. Tarayicida <b>dashboard.cloudflare.com</b> adresine git.<br/>
            2. Hesabin varsa giris yap, yoksa yeni hesap olustur.<br/>
            3. Baslangic icin <b>workers.dev</b> adresi yeterlidir.<br/>
            4. Sol menude <b>Workers &amp; Pages</b> bolumunu goreceksin. Deploy sonrasi Worker burada listelenir.<br/>
            5. D1 veritabani <b>Storage &amp; Databases -&gt; D1</b>, R2 bucket ise <b>R2 Object Storage</b> bolumunde gorunur.<br/><br/>

            <b>7. Node.js ve Wrangler kontrolu</b><br/>
            1. PowerShell ac.<br/>
            2. <tt>node -v</tt> yaz; bir surum numarasi donmeli.<br/>
            3. <tt>npm -v</tt> yaz; bu da surum donmeli.<br/>
            4. Sonra ana klasordeki <tt>backend-kurulum-yardimcisi.bat</tt> dosyasini calistir.<br/>
            • Bu dosya node, npm ve backend dosyalarini kontrol eder; eksik varsa seni durdurup acikca uyarir.<br/>
            • R2 zorunlu degildir. Cloudflare hesabinda R2 acik degilse veya simdi istemiyorsan bu adimi atlayabilirsin.<br/><br/>

            <b>8. D1 ve R2 adimlari</b><br/>
            • Backend yardimcisi senden Worker adi ve D1 veritabani adi ister. R2 ise artik opsiyoneldir.<br/>
            • Bu bilgiler bir yerden kopyalanan hazir degerler degildir; kurulum sirasinda <b>senin verdigin adlardir</b>.<br/>
            • Worker adi deploy sonrasi cikacak <b>workers.dev</b> adresinin proje kismi olur. D1 adi Cloudflare D1 listesinde gorunecek isimdir. R2 bucket adi ise sadece R2 ekleyeceksen gerekir.<br/>
            • Ornek: <tt>benim-uzaktan-kontrolum</tt>, <tt>uzaktan-kontrol-db</tt>, <tt>uzaktan-kontrol-files</tt><br/>
            • D1'i bu kurulumda olusturman gerekir. R2'yi panelden elle acmak zorunda degilsin. Script bunu simdi veya sonradan yapabilir.<br/>
            • Script, <tt>wrangler.jsonc</tt> dosyasini olusturduktan sonra D1 migration dosyalarini uzak veritabanina otomatik uygular.<br/><br/>

            <b>9. Firebase projesi ve Android app</b><br/>
            1. Tarayicida <b>console.firebase.google.com</b> adresini ac ve yeni bir proje olustur.<br/>
            2. Proje acildiktan sonra orta alandaki Android simgesine veya <b>Add app</b> dugmesine bas.<br/>
            3. Package name olarak <tt>com.uzaktanbildirim.mobile</tt> gir.<br/>
            4. Firebase'in verdigi <tt>google-services.json</tt> dosyasini indir.<br/>
            5. Bu dosyayi <b>UzaktanBildirimKurulum\\apk\\app\\google-services.json</b> yoluna koy.<br/>
            • Bu dosya olmadan APK build baslatilmaz.<br/><br/>

            <b>10. Firebase service account ve Worker secretlari</b><br/>
            1. Firebase projesinde sol ustteki disli simgesine tikla ve <b>Project settings</b> ekranini ac.<br/>
            2. Ustteki <b>Service accounts</b> sekmesine gir ve yeni bir private key JSON dosyasi olustur.<br/>
            3. Buradan <b>project_id</b>, <b>client_email</b> ve <b>private_key</b> bilgilerini al.<br/>
            4. Backend yardimcisi seni <tt>wrangler secret put FIREBASE_PROJECT_ID</tt>, <tt>FIREBASE_CLIENT_EMAIL</tt> ve <tt>FIREBASE_PRIVATE_KEY</tt> adimlarina yonlendirir.<br/>
            • Secret degerleri APK veya EXE icine gomulmez.<br/><br/>

            <b>11. Worker deploy ve Worker URL</b><br/>
            1. D1, R2 ve Firebase secretlari tamamlandiktan sonra Worker deploy edilir.<br/>
            2. Deploy bitince sana bir <b>workers.dev</b> adresi verilir.<br/>
            3. Bu adres senin <b>Worker URL</b> bilgin olur.<br/>
            4. Worker URL alanina sadece deploy ciktisinda yazan tam <tt>https://...workers.dev</tt> adresini aynen yapistir.<br/>
            5. Sadece Worker adini, sadece subdomain'i veya bunlari elle birlestirdigin bir adresi yazma.<br/>
            6. Dilersen sonuna <tt>/health</tt> ekleyerek hizli kontrol yapabilirsin.<br/><br/>

            <b>12. D1 tablolari eksikse ne yapacaksin?</b><br/>
            1. Windows ajanda <b>error code: 1101</b> gorursen genelde Worker ayaga kalkmistir ama D1 tablolari eksik kalmistir.<br/>
            2. Ana klasordeki <tt>d1-tablolari-onar-yardimcisi.bat</tt> dosyasini calistir.<br/>
            3. Bu dosya migration dosyalarini mevcut uzak D1 veritabanina tekrar uygular ve guncel Worker kodunu yeniden deploy eder.<br/>
            4. Islem bitince Windows ajanda yeniden <b>Kaydet / Baglan</b> demen yeterlidir. Ayni Worker URL ile devam edersin; yeni APK build gerekmez.<br/><br/>

            <b>13. R2'yi sonradan eklemek istersen</b><br/>
            1. Ana klasordeki <tt>r2-sonradan-ekle-yardimcisi.bat</tt> dosyasini calistir.<br/>
            2. Bu dosya mevcut <tt>wrangler.jsonc</tt> bilgilerini okur.<br/>
            3. Senden sadece R2 bucket adini ister.<br/>
            4. R2 bucket'i olusturur, config'i gunceller ve Worker'i yeniden deploy eder.<br/>
            5. Yeni APK build alman gerekmez.<br/><br/>

            <b>14. Windows ajani bagla</b><br/>
            1. <b>UzaktanBildirimKurulum\\exe</b> klasorune gir.<br/>
            2. <b>PcRemoteAgent.exe</b> dosyasini ac.<br/>
            3. Worker URL alanina kendi deploy ettigin tam <tt>https://...workers.dev</tt> adresini yaz.<br/>
            4. <b>Kaydet / Baglan</b> dugmesine bas.<br/>
            5. Basarili olursa pairing code hazir olur.<br/><br/>

            <b>15. APK build al</b><br/>
            1. Ana klasordeki <tt>apk-build-yardimcisi.bat</tt> dosyasini calistir.<br/>
            2. Android SDK genelde Android Studio ile kurulur. Android Studio kuruluysa <b>More Actions -&gt; SDK Manager</b> veya proje acikken <b>Settings -&gt; Android SDK</b> ekranindan SDK yolunu gorebilirsin.<br/>
            3. Bu dosya Java, Android SDK, local.properties, Gradle wrapper ve <tt>google-services.json</tt> kontrolu yapar.<br/>
            4. Eksik varsa sebebini yazar ve pencereyi acik tutar.<br/>
            5. Her sey tamamsa debug APK build alir ve olusan dosyanin yolunu gosterir.<br/><br/>

            <b>16. Telefona kur ve pairing yap</b><br/>
            1. Build edilen APK'yi telefona kur.<br/>
            2. Uygulamayi ac ve Worker URL alanina Windows ajanda kullandigin adresin aynisini yaz.<br/>
            3. Windows ajaninda gorunen pairing code'u telefona gir.<br/>
            4. Basarili eslestirme sonrasi artik bildirimler, screenshot, dosya islemleri ve diger komutlar kendi altyapin uzerinden calisir.<br/><br/>

            <b>17. En sik takildigin yerler</b><br/>
            • <b>google-services.json nereye konacak?</b> <b>UzaktanBildirimKurulum\\apk\\app\\google-services.json</b><br/>
            • <b>Worker URL ne zaman olusur?</b> <tt>npx wrangler deploy</tt> tamamlandiktan sonra<br/>
            • <b>Worker URL'yi nasil girecegim?</b> Sadece deploy ciktisindaki tam <tt>https://...workers.dev</tt> adresini aynen kopyalayacaksin<br/>
            • <b>R2 acik degilse ne olacak?</b> Temel kurulum devam eder; istersen sonradan R2 ekleyebilirsin<br/>
            • <b>error code: 1101 ne demek?</b> Genelde D1 tablolari eksiktir; <tt>d1-tablolari-onar-yardimcisi.bat</tt> calistir. Bu arac migrationlari tekrar uygular ve Worker'i yeniden deploy eder<br/>
            • <b>EXE neden acilmiyor?</b> Dll veya json dosyalarindan biri ayni klasorde olmayabilir<br/>
            • <b>Build neden baslamiyor?</b> Genelde Java, Android SDK veya google-services.json eksigi vardir<br/><br/>

            <b>18. Kisa kontrol listesi</b><br/>
            1. Masaustunde <b>UzaktanBildirimKurulum</b> klasoru hazir mi?<br/>
            2. exe, apk ve backend klasorleri dolu mu?<br/>
            3. Cloudflare hesabina girildi mi?<br/>
            4. Firebase projesi ve Android app olusturuldu mu?<br/>
            5. <tt>google-services.json</tt> dogru yere kondu mu?<br/>
            6. D1, R2 ve Worker deploy tamamlandi mi?<br/>
            7. Windows ajan ayni Worker URL ile baglandi mi?<br/>
            8. Build edilen APK ayni Worker URL ile ayarlandi mi?<br/>
            9. Pairing code ile eslestirme basarili mi?<br/>
            10. /health ve ilk test komutlari temiz mi?
        """.trimIndent()

        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }
}
