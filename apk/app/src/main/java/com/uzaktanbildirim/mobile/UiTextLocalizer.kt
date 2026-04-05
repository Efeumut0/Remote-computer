package com.uzaktanbildirim.mobile

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object UiTextLocalizer {
    private val exactMap = linkedMapOf(
        "Uzaktan Kontrol" to "Remote Control",
        "Bildirim, dosya ve sistem kontrolu." to "Notifications, files, and system control.",
        "Kisayol paneli" to "Shortcut panel",
        "Sirala" to "Reorder",
        "EXE, kisayol, klasor, URL, CMD, PowerShell ve Calistir komutlari ekleyebilirsin. Uzun basarak duzenle veya sil." to "You can add EXE, shortcut, folder, URL, CMD, PowerShell, and Run commands. Long press to edit or delete.",
        "Kartlara dokununca secili PC uzerinde calisir. Linkler varsayilan tarayicida, protokol linkleri destekleyen uygulamada acilir." to "Tap a tile to run it on the selected PC. Links open in the default browser or in the app that handles that protocol.",
        "Henuz kısayol eklenmedi. Son karttaki arti ile yeni bir tane ekleyebilirsin." to "No shortcuts yet. Use the plus tile to add one.",
        "Ilk kurulum rehberini ac" to "Open the initial setup guide",
        "Worker URL" to "Worker URL",
        "Telefon adi" to "Phone name",
        "Ayarlarimi kaydet" to "Save my settings",
        "Worker dosya modu: Kontrol ediliyor." to "Worker file mode: Checking.",
        "Arka plan calisma izni" to "Background activity permission",
        "Arka plan izni durumu kontrol ediliyor." to "Checking background permission status.",
        "Izni iste" to "Request permission",
        "Durumu kontrol et" to "Check status",
        "Temiz kurulumda Worker URL ve secili PC APK'nin icine gomulmez. Baglanti bilgilerini sonradan sen girersin." to "In a clean setup, the Worker URL and selected PC are not embedded into the APK. You enter the connection details later.",
        "Eslestirme kodu" to "Pairing code",
        "PC ile eslestir" to "Pair with PC",
        "PC durumunu yenile" to "Refresh PC status",
        "Secili PC: -" to "Selected PC: -",
        "Bilinmiyor" to "Unknown",
        "Eslemeyi kaldir" to "Remove pairing",
        "Secili Worker: R2 destegi kontrol ediliyor." to "Selected Worker: checking R2 support.",
        "Coklu PC secimi" to "Multi-PC selection",
        "Durum burada gorunecek." to "Status will appear here.",
        "Hesaptaki PC'ler burada ozetlenecek." to "PCs in the account will be summarized here.",
        "Bildirim ayarlari" to "Notification settings",
        "Bildirim tercihleri burada gorunecek." to "Notification preferences will appear here.",
        "Ayarlari yukle" to "Load settings",
        "Ayarlari kaydet" to "Save settings",
        "PC acilinca bildir" to "Notify when the PC starts",
        "PC kapaninca bildir" to "Notify when the PC shuts down",
        "Ajan kapaninca bildir" to "Notify when the agent closes",
        "Uyku modunu bildir" to "Notify on sleep",
        "Uyandiginda bildir" to "Notify on wake",
        "Baglanti kesildiginde bildir" to "Notify on disconnect",
        "Basarisiz komutlari bildir" to "Notify on failed commands",
        "Bildirim listesi limiti" to "Notification list limit",
        "Ornek: 10" to "Example: 10",
        "Bildirim merkezinde ayni anda kac bildirim gosterilecegini belirler. 5 ile 50 arasi bir deger kullan." to "Sets how many notifications are shown in the notification center at once. Use a value between 5 and 50.",
        "Guvenlik ve oturum" to "Security and session",
        "Kilitle" to "Lock",
        "Zorla kapat" to "Force shutdown",
        "Zorla yeniden baslat" to "Force restart",
        "Oturumu kapat" to "Log off",
        "Medya ve ses" to "Media and sound",
        "Sonraki" to "Next",
        "Onceki" to "Previous",
        "Ses +" to "Volume +",
        "Ses -" to "Volume -",
        "Mute / Sessize al" to "Mute / Unmute",
        "Uygulama baslat" to "Launch app",
        "Chrome + Spotify + Discord ac" to "Open Chrome + Spotify + Discord",
        "Custom uygulama yolu veya alias" to "Custom app path or alias",
        "Custom uygulama argumanlari (opsiyonel)" to "Custom app arguments (optional)",
        "Secili custom uygulamayi calistir" to "Run selected custom app",
        "Calisan islemler" to "Running processes",
        "Acik uygulamalar" to "Open apps",
        "Tum islemler" to "All processes",
        "Secilen process adi buraya gelir" to "The selected process name appears here",
        "Secili process'i kapat" to "Close selected process",
        "Sistem bilgisi" to "System information",
        "Sistem bilgilerini yenile" to "Refresh system information",
        "Sistem bilgileri burada gorunecek." to "System information will appear here.",
        "Clipboard ve klavye" to "Clipboard and keyboard",
        "Clipboard metni" to "Clipboard text",
        "Clipboard gonder" to "Send clipboard",
        "Clipboard oku" to "Read clipboard",
        "Temizle" to "Clear",
        "Clipboard senkronizasyonunu ac" to "Enable clipboard sync",
        "Clipboard senkronizasyonu kapali." to "Clipboard sync is off.",
        "Klavye ile gonderilecek metin" to "Text to send via keyboard",
        "Metni yaz" to "Type text",
        "Mouse" to "Mouse",
        "Touchpad alanı istekleri throttle'lar; normal kişisel kullanımda limiti çok sert zorlamaz." to "The touchpad area throttles requests; in normal personal use it does not hit the limit aggressively.",
        "Drag modu kapali" to "Drag mode off",
        "Orta tik" to "Middle click",
        "Yukari" to "Up",
        "Asagi" to "Down",
        "Sola" to "Left",
        "Saga" to "Right",
        "Sol tik" to "Left click",
        "Sag tik" to "Right click",
        "Scroll +" to "Scroll +",
        "Scroll -" to "Scroll -",
        "Dosya gezgini" to "File explorer",
        "Uzak klasor yolu (bos birak = diskler)" to "Remote folder path (leave blank = drives)",
        "Diskleri listele" to "List drives",
        "Klasoru listele" to "List folder",
        "Ust klasor" to "Parent folder",
        "Telefondan dosya yukle" to "Upload file from phone",
        "Secili dosya: -" to "Selected file: -",
        "Coklu secim kapali." to "Multi-select is off.",
        "Yeni klasor adi" to "New folder name",
        "Mevcut klasorde yeni klasor olustur" to "Create a new folder in the current folder",
        "Secili oge icin yeni ad" to "New name for selected item",
        "Secili dosyayi calistir" to "Run selected file",
        "Secili dosyayi indir" to "Download selected file",
        "Secili dosyayi onizle" to "Preview selected file",
        "Secimi temizle" to "Clear selection",
        "Secili ogeyi yeniden adlandir" to "Rename selected item",
        "Secili ogeyi sil" to "Delete selected item",
        "Secilen ogeleri toplu sil" to "Delete selected items",
        "Aktarim bekleniyor." to "Waiting for transfer.",
        "Bir klasore dokununca acilir. Uzun basarak oge sec, dosyaya dokunarak calistirma/indirme akisini kullan." to "Tap a folder to open it. Long press to select an item, and tap a file to use the run/download flow.",
        "Ekran goruntusu" to "Screen capture",
        "Dosya onizleme" to "File preview",
        "Secili dosyanin hizli onizlemesi burada gosterilir." to "A quick preview of the selected file appears here.",
        "Screenshot iste" to "Request screenshot",
        "Buyut / ac" to "Zoom / open",
        "Screenshot indir" to "Download screenshot",
        "Resim / screenshot secip yukle" to "Choose and upload image / screenshot",
        "Orijinal canli onizleme" to "Original live preview",
        "Canli onizlemeyi durdur" to "Stop live preview",
        "1080p canli onizleme baslat" to "Start 1080p live preview",
        "Canli ekran onizlemesi kapali." to "Live screen preview is off.",
        "Son sonuc" to "Latest result",
        "Son komut sonucu burada gorunecek." to "The latest command result will appear here.",
        "Log" to "Log",
        "Yaklasik limit bilgisi burada gorunecek." to "Estimated limit information will appear here.",
        "Son 5 bildirim" to "Last 5 notifications",
        "Son 5 bildirim burada gorunecek." to "The last 5 notifications will appear here.",
        "Merkezi yenile" to "Refresh center",
        "Tumunu okundu yap" to "Mark all as read",
        "Arka plan izni onerilir" to "Background permission recommended",
        "Ilk kullanim" to "First use",
        "Bu uygulamayi ilk defa mi kullaniyorsunuz?" to "Are you using this app for the first time?",
        "Evet ilk defa kullaniyorum" to "Yes, I am using it for the first time",
        "Ilk kurulum rehberi" to "Initial setup guide",
        "Secili PC'yi degistir" to "Change selected PC",
        "Eslesmeyi kaldir" to "Remove pairing",
        "Kaldir" to "Remove",
        "Kisayol ekle" to "Add shortcut",
        "Kisayolu duzenle" to "Edit shortcut",
        "Kaydet" to "Save",
        "Guncelle" to "Update",
        "Kisayolu sil" to "Delete shortcut",
        "Sil" to "Delete",
        "PC'den uygulama veya kisayol sec" to "Pick an app or shortcut from the PC",
        "PC'den klasor sec" to "Pick a folder from the PC",
        "PC Screenshot" to "PC screenshot",
        "Bildirim ayarlari yuklendi." to "Notification settings loaded.",
        "Bildirim ayarlari kaydedildi." to "Notification settings saved.",
        "Bildirim ayarlari guncellendi." to "Notification settings updated.",
        "PC clipboard'u telefona alindi." to "PC clipboard received on the phone.",
        "Kapat" to "Close",
        "Iptal" to "Cancel",
        "Evet" to "Yes",
        "Hayir" to "No"
    )

    private val regexRules = listOf(
        Regex("""^Secili PC: (.+)$""") to { m: MatchResult -> "Selected PC: ${m.groupValues[1]}" },
        Regex("""^Yerel IP'ler: (.+)$""") to { m: MatchResult -> "Local IPs: ${m.groupValues[1]}" },
        Regex("""^Dis IP: (.+)$""") to { m: MatchResult -> "External IP: ${m.groupValues[1]}" },
        Regex("""^OS surumu: (.+)$""") to { m: MatchResult -> "OS version: ${m.groupValues[1]}" },
        Regex("""^PC secildi: (.+)$""") to { m: MatchResult -> "PC selected: ${m.groupValues[1]}" },
        Regex("""^Telefon eslesti: (.+)$""") to { m: MatchResult -> "Phone paired: ${m.groupValues[1]}" },
        Regex("""^Kisayol hazir: (.+)$""") to { m: MatchResult -> "Shortcut ready: ${m.groupValues[1]}" },
        Regex("""^Kisayol silindi: (.+)$""") to { m: MatchResult -> "Shortcut deleted: ${m.groupValues[1]}" },
        Regex("""^Kisayol sona tasindi: (.+)$""") to { m: MatchResult -> "Shortcut moved to end: ${m.groupValues[1]}" },
        Regex("""^Kisayol sirasi guncellendi: (.+)$""") to { m: MatchResult -> "Shortcut order updated: ${m.groupValues[1]}" },
        Regex("""^Process secildi: (.+)$""") to { m: MatchResult -> "Process selected: ${m.groupValues[1]}" },
        Regex("""^PC durumu guncellendi\.$""") to { _: MatchResult -> "PC status refreshed." },
        Regex("""^Makine: (.+)$""") to { m: MatchResult -> "Machine: ${m.groupValues[1]}" },
        Regex("""^Kullanici: (.+)$""") to { m: MatchResult -> "User: ${m.groupValues[1]}" },
        Regex("""^OS: (.+)$""") to { m: MatchResult -> "OS: ${m.groupValues[1]}" },
        Regex("""^Islemci cekirdegi: (.+)$""") to { m: MatchResult -> "CPU cores: ${m.groupValues[1]}" },
        Regex("""^RAM: (.+)$""") to { m: MatchResult -> "RAM: ${m.groupValues[1]}" },
        Regex("""^Surec sayisi: (.+)$""") to { m: MatchResult -> "Process count: ${m.groupValues[1]}" },
        Regex("""^Diskler:$""") to { _: MatchResult -> "Drives:" },
        Regex("""^(.+) icin clipboard senkronizasyonu hazir\. Telefonda kopyalanan yazi uygulama acikken gonderilir\.$""") to { m: MatchResult -> "Clipboard sync is ready for ${m.groupValues[1]}. Text copied on the phone is sent while the app is open." },
        Regex("""^(.+) icin clipboard senkronizasyonu kapali\.$""") to { m: MatchResult -> "Clipboard sync is off for ${m.groupValues[1]}." },
        Regex("""^(.+) icin canli ekran onizlemesi hazir \((.+)\)\.$""") to { m: MatchResult -> "Live screen preview is ready for ${m.groupValues[1]} (${m.groupValues[2]})." },
        Regex("""^(.+) icin canli ekran onizlemesi kapali\.$""") to { m: MatchResult -> "Live screen preview is off for ${m.groupValues[1]}." },
        Regex("""^Canli onizleme acik • (.+) • (\d+) x (\d+)$""") to { m: MatchResult -> "Live preview active • ${m.groupValues[1]} • ${m.groupValues[2]} x ${m.groupValues[3]}" },
        Regex("""^Clipboard senkronizasyonu acik\. Uygulama acikken telefondan kopyaladigin yazi (.+) cihazina gonderilir\. Yeni PC kopyalamalari otomatik gelir\.$""") to { m: MatchResult -> "Clipboard sync is on. While the app is open, text copied on the phone is sent to ${m.groupValues[1]}. New PC clipboard updates arrive automatically." },
        Regex("""^Clipboard senkronizasyonu acik\. Uygulama acikken telefondan kopyaladigin yazi (\d+) etkin PC'ye gonderilir\. Yeni PC kopyalamalari otomatik gelir\.$""") to { m: MatchResult -> "Clipboard sync is on. While the app is open, text copied on the phone is sent to ${m.groupValues[1]} active PCs. New PC clipboard updates arrive automatically." },
        Regex("""^Yerel clipboard (.+) cihazina gonderildi\.$""") to { m: MatchResult -> "Local clipboard sent to ${m.groupValues[1]}." },
        Regex("""^Yerel clipboard (\d+) etkin PC'ye gonderildi\.$""") to { m: MatchResult -> "Local clipboard sent to ${m.groupValues[1]} active PCs." },
        Regex("""^Yerel ayarlar kaydedildi\.$""") to { _: MatchResult -> "Local settings saved." },
        Regex("""^FCM token alindi\.$""") to { _: MatchResult -> "FCM token received." },
        Regex("""^FCM token alinamadi: (.+)$""") to { m: MatchResult -> "Could not receive FCM token: ${m.groupValues[1]}" },
        Regex("""^Worker URL degisti\. Eski eslesme ve oturum bilgileri temizlendi\.$""") to { _: MatchResult -> "Worker URL changed. Old pairing and session data were cleared." },
        Regex("""^Worker hatasi \(1101\): (.+)$""") to { m: MatchResult -> "Worker error (1101): ${m.groupValues[1]}" },
        Regex("""^Canli ekran onizlemesi baslatildi \((.+)\)\.$""") to { m: MatchResult -> "Live screen preview started (${m.groupValues[1]})." },
        Regex("""^Canli ekran onizlemesi icin once bir PC sec\.$""") to { _: MatchResult -> "Select a PC first for live screen preview." },
        Regex("""^Clipboard sync icin once bir PC sec\.$""") to { _: MatchResult -> "Select a PC first for clipboard sync." },
        Regex("""^Once PC ile esles\.$""") to { _: MatchResult -> "Pair with a PC first." },
        Regex("""^Kisayollar icin once Worker ayarini girip bir PC sec\.$""") to { _: MatchResult -> "Enter the Worker settings and select a PC first for shortcuts." },
        Regex("""^Hotkey icin bir ana tus sec\.$""") to { _: MatchResult -> "Select a primary key for the hotkey." },
        Regex("""^Ctrl \+ Alt \+ Delete desteklenmiyor\.$""") to { _: MatchResult -> "Ctrl + Alt + Delete is not supported." },
        Regex("""^Kisayol hedefi bos olamaz\.$""") to { _: MatchResult -> "Shortcut target cannot be empty." },
        Regex("""^Kopyalanacak esleme kodu yok\.$""") to { _: MatchResult -> "There is no pairing code to copy." },
        Regex("""^Esleme kodu panoya kopyalandi\.$""") to { _: MatchResult -> "Pairing code copied to the clipboard." },
        Regex("""^Bagli$""") to { _: MatchResult -> "Connected" },
        Regex("""^Bagli degil$""") to { _: MatchResult -> "Not connected" },
        Regex("""^Eslendi$""") to { _: MatchResult -> "Paired" },
        Regex("""^Esleme bekliyor$""") to { _: MatchResult -> "Waiting for pairing" }
    )

    fun translate(text: CharSequence?): String {
        val raw = text?.toString().orEmpty()
        if (!BuildConfig.FORCE_ENGLISH || raw.isBlank()) {
            return raw
        }
        return raw.split("\n").joinToString("\n") { translateLine(it) }
    }

    private fun translateLine(line: String): String {
        if (line.isBlank()) {
            return line
        }
        val bulletPrefix = if (line.startsWith("• ")) "• " else ""
        val content = if (bulletPrefix.isNotEmpty()) line.removePrefix(bulletPrefix) else line
        exactMap[content]?.let { return bulletPrefix + it }
        for ((regex, replacement) in regexRules) {
            val match = regex.matchEntire(content) ?: continue
            return bulletPrefix + replacement(match)
        }
        return bulletPrefix + content
    }

    fun applyToViewTree(root: View?) {
        if (!BuildConfig.FORCE_ENGLISH || root == null) {
            return
        }
        when (root) {
            is TextView -> {
                val translatedText = translate(root.text)
                if (translatedText.isNotBlank() && translatedText != root.text?.toString()) {
                    root.text = translatedText
                }
                val translatedHint = translate(root.hint)
                if (translatedHint.isNotBlank() && translatedHint != root.hint?.toString()) {
                    root.hint = translatedHint
                }
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyToViewTree(root.getChildAt(index))
            }
        }
    }
}
