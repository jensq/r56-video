# R56 video

Enkel videospelare för Android TV / Chromecast med Google TV. Bläddrar i SMB-mappar, spelar upp
video (även .rar-delade, okomprimerade arkiv: .rar/.r00/.r01… eller .part1.rar/.part2.rar…),
kommer ihåg sedda filer och var du slutade. Gränssnittet är på engelska och efterliknar Kodi.

## Bygga APK:n

### Alternativ A – GitHub (inget behöver installeras)
1. Skapa ett nytt (privat) repo på github.com och ladda upp hela innehållet i den här mappen
   (inklusive den dolda mappen `.github`). Går det inte att dra in `.github` i webbläsaren:
   välj "Add file → Create new file", skriv `.github/workflows/build.yml` som filnamn och klistra in
   innehållet från filen med samma namn.
2. Gå till fliken **Actions** → **Build APK** → **Run workflow** (startar även vid varje push).
3. När bygget är klart (några minuter): öppna körningen och ladda ner artefakten **R56-video-apk**
   (zip med `R56-video.apk`).

### Alternativ B – Android Studio
Öppna mappen i Android Studio → *Build → Build Bundle(s) / APK(s) → Build APK(s)*.
APK:n hamnar i `app/build/outputs/apk/debug/app-debug.apk`.

## Installera på Chromecast med Google TV
1. Inställningar → System → Om → tryck 7 gånger på "Android TV OS-version" (aktiverar utvecklaralternativ).
2. Inställningar → System → Utvecklaralternativ → slå på **USB-felsökning** (eller trådlös felsökning).
3. På datorn (samma nätverk): `adb connect <TV:ns IP-adress>:5555` och sedan `adb install -r R56-video.apk`.
   (Alternativt appen "Downloader" på TV:n om du lägger APK:n på en webbadress.)

## Använda appen
* **Add location**: skriv adress som `smb://192.168.1.10/media/Filmer`, användarnamn/lösenord
  (tomt = gäst). `DOMÄN\användare` fungerar. Varje sparad plats är en favorit.
* I mappvyn: *Add folder to favorites* sparar aktuell mapp, *Toggle watched* markerar vald fil
  som sedd/osedd (vänster meny nås med riktningsknapp vänster).
* Under uppspelning: OK/upp/ner visar kontrollerna, vänster/höger spolar −10 s / +30 s.
  Knapparna längst ner: bakåt, play/pause, stop, framåt, ljudspår, undertexter.
* Externa undertexter (.srt/.ass/.vtt med samma namn som videon) hittas automatiskt.

## Begränsningar
* Endast SMB2/SMB3 (alla moderna NAS/Windows/Samba). SMB1 stöds inte.
* RAR: endast arkiv som lagrats utan komprimering ("store", det vanliga för video) och utan lösenord.
* Format som ExoPlayer inte kan (t.ex. gamla .avi) spelas inte. DTS/TrueHD-ljud avkodas via FFmpeg-tillägget.
