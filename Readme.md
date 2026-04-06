# Remote Notification Initial Setup Guide

This package is prepared for users who want to create their own Cloudflare Worker backend and build their own APK with their own Firebase project.

## 1. Prepare the main folder

1. Open the `UzaktanBildirimKurulum` folder on the desktop.
2. Make sure these folders exist inside it:
   - `exe`
   - `apk`
   - `backend`
3. Helper files will also be in the main folder:
   - `backend-setup-helper.bat`
   - `apk-build-helper.bat`
   - `repair-d1-tables-helper.bat`
   - `add-r2-later-helper.bat`

## 2. Windows agent files

These 4 files must stay together inside the `exe` folder:

- `PcRemoteAgent.exe`
- `PcRemoteAgent.dll`
- `PcRemoteAgent.deps.json`
- `PcRemoteAgent.runtimeconfig.json`

Do not separate these files. `PcRemoteAgent.exe` should not be moved alone.

## 3. Prepare your Cloudflare account

1. Open [dash.cloudflare.com](https://dash.cloudflare.com).
2. Sign in or create an account.
3. For the first setup, `workers.dev` is enough.
4. Your deployed Worker will appear under `Workers & Pages`.
5. D1 databases appear under `Storage & Databases -> D1`.
6. R2 buckets appear under `R2 Object Storage`.

## 4. Check Node.js

1. Open PowerShell.
2. Run `node -v`.
3. Run `npm -v`.
4. If either one does not return a version number, install Node.js LTS first.

## 5. Start the backend setup

1. Run `backend-setup-helper.bat` in the main folder.
2. This script:
   - checks `node` and `npm`
   - checks required files in the `backend` folder
   - runs `npm install`
   - guides you through `npx wrangler login` and `npx wrangler whoami`
   - asks for the Worker name and D1 database name
   - asks whether you want to enable R2 now
   - creates `wrangler.jsonc` from a template
   - automatically applies the D1 migration files to the remote database
   - helps you set Firebase service account secrets
   - takes you all the way to `npx wrangler deploy`

You do not copy these names from somewhere else. You choose them yourself:

- `Worker name`: the project part of the final `workers.dev` address. Example: `my-remote-control`
- `D1 database name`: the name shown in the Cloudflare D1 list. Example: `remote-control-db`
- `R2 bucket name`: if you choose R2 now, this is the name shown in the Cloudflare R2 list. Example: `remote-control-files`

Where you will see them later:

- `Worker`: `Workers & Pages`
- `D1`: `Storage & Databases -> D1`
- `R2`: `R2 Object Storage`

Note: D1 is required for this setup. R2 is optional. If R2 is not enabled on the account yet, you can skip it and add it later.

## 6. Prepare your Firebase project

1. Open [console.firebase.google.com](https://console.firebase.google.com).
2. Click `Add project` and create a new project.
3. After the project opens, click the Android icon or `Add app`.
4. Use `com.uzaktanbildirim.mobile` as the package name.
5. Download the `google-services.json` file given by Firebase.
6. Put that file here:

`UzaktanBildirimKurulum\apk\app\google-services.json`

## 7. Firebase service account

1. In Firebase, click the gear icon and open `Project settings`.
2. Open the `Service accounts` tab.
3. Use `Generate new private key` and download the JSON file.
4. `backend-setup-helper.bat` will ask for this file path.
5. The script reads:
   - `project_id`
   - `client_email`
   - `private_key`
   and sends them to Cloudflare with `wrangler secret put`.

## 8. Worker URL

1. `backend-setup-helper.bat` runs `npx wrangler deploy` near the end.
2. After deploy finishes, a `workers.dev` address is created.
3. That address is your `Worker URL`.
4. Use the same address in both the Windows agent and the Android app.
5. Copy only the exact full `https://...workers.dev` address printed by deploy.
6. Do not enter only the Worker name, only the subdomain, or a guessed address you manually joined together.
7. You can view it later again in `Workers & Pages`.
8. The current backend helper also saves this address in `worker-url.txt` in the main folder.
8. If you finished setup without R2, the system still works with the same Worker URL.

## 9. If D1 tables are missing

1. If the Windows agent shows `error code: 1101`, the Worker may be deployed but the D1 schema, Durable Object binding, or the generated `wrangler.jsonc` may still be incomplete.
2. Run `repair-d1-tables-helper.bat` in the main folder.
3. That helper rebuilds the Worker config from your current `wrangler.jsonc`, reapplies the D1 migrations, and redeploys the current Worker code.
4. It also runs a quick `/health` and `/api/mobile/pair` verification after redeploy.
5. After it finishes, click `Save / Connect` again in the Windows agent. You can keep using the same Worker URL and you do not need a new APK build.

## 10. If you want to add R2 later

1. Run `add-r2-later-helper.bat` in the main folder.
2. It reads the current `wrangler.jsonc`.
3. It only asks for the R2 bucket name.
4. It creates the bucket, updates the config, and redeploys the Worker.
5. You do not need a new APK build.
6. The same Worker URL will continue working with R2 enabled.

## 11. Connect the Windows agent

1. Open the `exe` folder.
2. Run `PcRemoteAgent.exe`.
3. Enter your full deployed `https://...workers.dev` Worker URL.
4. Click `Save / Connect`.
5. After a successful connection, the pairing code will be ready.

## 12. Build the APK

1. Run `apk-build-helper.bat` in the main folder.
2. The Android SDK usually comes with Android Studio. If Android Studio is not installed, the SDK is probably missing too.
3. If Android Studio is installed, you can find the SDK path in `Android Studio -> More Actions -> SDK Manager` or `Settings -> Android SDK`.
4. This script checks:
   - Java
   - Android SDK
   - `local.properties`
   - `google-services.json`
   - `gradlew.bat`
5. If `local.properties` is missing, it tries `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and common Windows SDK folders first.
6. If it still cannot find the SDK, it asks you for the SDK path.
7. If anything is missing, it stops and explains the problem.
8. If everything is correct, it builds the debug APK.

## 13. Install on the phone and pair

1. Install the generated `app-debug.apk` on the phone.
2. Open the app.
3. Enter the same Worker URL used in Windows.
4. Enter the pairing code shown in the Windows agent.
5. After successful pairing, the system works on your own backend.

## 14. Common issues

- If `google-services.json` is missing, the APK build will not start.
- If the Android SDK cannot be found automatically, the script will ask you for the SDK path.
- The Android SDK usually comes with Android Studio.
- After D1 is created, you can check it under `Storage & Databases -> D1`. After R2 is created, check `R2 Object Storage`. After Worker deploy, check `Workers & Pages`.
- If R2 is disabled, the main setup can still finish. You can add R2 later with `add-r2-later-helper.bat`.
- The Worker URL only exists after deploy.
- In the Worker URL field, enter only the exact full `https://...workers.dev` address from deploy output.
- If the EXE does not start, one of the required `.dll` or `.json` files is probably missing.
- If pairing fails, the Worker URL may be wrong or the Windows agent may not be connected.
- If the agent shows `error code: 1101`, run `repair-d1-tables-helper.bat`. That helper repairs the Worker config, reapplies the migrations, and redeploys the Worker.
- If notifications do not arrive, check the Firebase service account and Worker secret steps.

Bug finder Bektaş Eren
