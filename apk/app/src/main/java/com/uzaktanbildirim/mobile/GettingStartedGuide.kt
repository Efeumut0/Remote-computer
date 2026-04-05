package com.uzaktanbildirim.mobile

import android.text.Spanned
import androidx.core.text.HtmlCompat

object GettingStartedGuide {
    fun buildGuideText(): Spanned {
        val html = """
            <b>Remote Notification Initial Setup Guide</b><br/>
            This guide explains the full setup flow: prepare the desktop setup folder, deploy your own Cloudflare Worker, connect your own Firebase project, build your own APK, then pair the Windows agent and the phone with the same Worker URL.<br/><br/>

            <b>1. Prepare the main folder</b><br/>
            1. Create a folder named <b>UzaktanBildirimKurulum</b> on the desktop.<br/>
            2. Inside it, create <b>exe</b>, <b>apk</b>, and <b>backend</b> folders.<br/>
            3. Put the Windows agent files into <b>exe</b>, the Android project into <b>apk</b>, and the Worker template into <b>backend</b>.<br/><br/>

            <b>2. Windows agent files</b><br/>
            Keep these four files together in the <b>exe</b> folder:<br/>
            • <b>PcRemoteAgent.exe</b><br/>
            • <b>PcRemoteAgent.dll</b><br/>
            • <b>PcRemoteAgent.deps.json</b><br/>
            • <b>PcRemoteAgent.runtimeconfig.json</b><br/><br/>

            <b>3. Cloudflare account</b><br/>
            1. Open <b>dashboard.cloudflare.com</b>.<br/>
            2. Sign in or create an account.<br/>
            3. <b>workers.dev</b> is enough for the first setup.<br/>
            4. Your Worker appears under <b>Workers &amp; Pages</b>.<br/>
            5. D1 appears under <b>Storage &amp; Databases -&gt; D1</b>.<br/>
            6. R2 appears under <b>R2 Object Storage</b>.<br/><br/>

            <b>4. Backend setup</b><br/>
            1. Run <tt>backend-setup-helper.bat</tt> from the main folder.<br/>
            2. Sign in with Wrangler when the browser opens.<br/>
            3. Enter a Worker name and a D1 database name when asked.<br/>
            4. R2 is optional. You can skip it and add it later.<br/>
            5. The helper applies D1 migrations and deploys the Worker for you.<br/><br/>

            <b>5. Firebase project</b><br/>
            1. Open <b>console.firebase.google.com</b> and create a project.<br/>
            2. Add an Android app.<br/>
            3. Use <tt>com.uzaktanbildirim.mobile</tt> as the package name.<br/>
            4. Download <tt>google-services.json</tt> and place it into <b>UzaktanBildirimKurulum\apk\app\google-services.json</b>.<br/><br/>

            <b>6. Firebase secrets for the Worker</b><br/>
            1. Open <b>Project settings -&gt; Service accounts</b> in Firebase.<br/>
            2. Create a private key JSON file.<br/>
            3. The backend helper uploads <tt>FIREBASE_PROJECT_ID</tt>, <tt>FIREBASE_CLIENT_EMAIL</tt>, and <tt>FIREBASE_PRIVATE_KEY</tt> as Worker secrets.<br/><br/>

            <b>7. Worker URL</b><br/>
            1. After deploy, Cloudflare gives you a full <tt>https://...workers.dev</tt> address.<br/>
            2. Use that full URL exactly as shown.<br/>
            3. Do not guess or manually combine the address.<br/>
            4. You can append <tt>/health</tt> to test it.<br/><br/>

            <b>8. If D1 is missing</b><br/>
            If you see <b>error code: 1101</b>, run <tt>repair-d1-tables-helper.bat</tt>. It reapplies D1 migrations and redeploys the Worker.<br/><br/>

            <b>9. If you want R2 later</b><br/>
            Run <tt>add-r2-later-helper.bat</tt>. It adds the R2 bucket to the existing Worker config and redeploys the Worker. A new APK build is not required.<br/><br/>

            <b>10. Build the APK</b><br/>
            1. Run <tt>apk-build-helper.bat</tt>.<br/>
            2. Make sure Java, Android SDK, and <tt>google-services.json</tt> are ready.<br/>
            3. The helper checks the requirements and runs the debug APK build.<br/><br/>

            <b>11. Pair the phone and the PC</b><br/>
            1. Open <b>PcRemoteAgent.exe</b> and enter the Worker URL.<br/>
            2. Open the app on the phone and enter the same Worker URL.<br/>
            3. Use the pairing code shown in the Windows agent.<br/>
            4. After pairing, notifications, screenshots, files, shortcuts, and system commands work through your own backend.<br/><br/>

            <b>12. Quick checklist</b><br/>
            • Are the <b>exe</b>, <b>apk</b>, and <b>backend</b> folders ready?<br/>
            • Did you deploy the Worker successfully?<br/>
            • Did you place <tt>google-services.json</tt> into the correct folder?<br/>
            • Did both the Windows agent and the Android app use the same Worker URL?<br/>
            • Did pairing succeed?<br/>
        """.trimIndent()

        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }
}
