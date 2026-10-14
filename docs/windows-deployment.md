# Windows 10 Pro deployment: a small PC, one month a year

For one ordinary Windows 10 Pro PC with **4 GB of RAM**, on the college network only, used about **one month a year**
(the appraisal window). No Linux, no Docker, no domain name. Everything runs as plain Windows programs.

Read section 0 once, then do the steps in order. Do not skip a step and do not change the order.

```
 college PCs --http://<server IP>--> website (Node, port 80)
                                        |-- /api/*  --> backend  (Java, port 8080, this PC only)  --> MySQL (port 3306, this PC only)
                                        '-- the rest -> served by the website itself
 Files for issued reports: C:\FAMS\data\documents      Backups: a second disk (USB drive)
```

## 0. The decisions made for you, and why

| Question | Answer used here | Why |
|---|---|---|
| How do people reach it? | `http://<the PC's IP address>`, for example `http://10.20.30.40` | No DNS name is needed. |
| Certificate / HTTPS? | **None. Plain HTTP.** | You decided no certificate is needed, which also removes the whole certificate installation. **The trade-off:** passwords and sessions cross the college network unencrypted, so anyone on the same network who can capture traffic can read them. Reduce that by using a wired, switched network, keeping the site open only during the season, and giving every user a password they use nowhere else. HTTPS can be switched on later (Appendix A; it needs one more small program). |
| What do users see? | The browser shows **"Not secure"** next to the address. That is expected. | Browsers label every plain-HTTP site this way. |
| A web server in front (Nginx, Caddy, IIS)? | **No.** The website program answers on port 80 itself and passes `/api` to the backend. | Fewer parts, less memory, nothing extra to install. **The cost:** the audit trail records `127.0.0.1` as the address of every sign-in instead of each person's PC address (the program cannot be told the real address without a proxy, and the guide deliberately does not trust a value a user could forge). |
| Docker? | No | A 4 GB PC cannot afford Windows plus Docker's hidden Linux machine. |
| Always on? | **No.** `fams-open.ps1` turns it on, `fams-close.ps1` turns it off. | Used one month a year: when it is off, nothing is reachable, nothing needs patching, and the PC is free for normal work. |
| Who can reach it? | Only PCs on the **same network segment** by default; widening is one command (step 12). | Safer by default. |

**What this is not.** Windows 10 is out of Microsoft's support, so keep this PC off the internet (no port forwarding, no
public address) and turn the site off with `fams-close.ps1` when the season ends.

**Memory.** Expect roughly 3.2 GB of 4 GB in use while the site is open (Windows about 2 GB, MySQL 0.4, backend 0.6,
website 0.15). It works for light use. Do not use the PC for heavy work while the site is open. A second 4 GB
memory stick is the best upgrade you can make.

## 1. What you need before you start

Tick every line.

- [ ] The PC: Windows 10 Pro 64-bit, 4 GB RAM, about 20 GB free disk, **a network cable** (not Wi-Fi), and an administrator account.
- [ ] A **fixed IP address** for it. Ask IT to reserve one, or use the PC's current address and have IT confirm nobody else gets it.
- [ ] A **USB hard drive or second disk** for backups. Write down its drive letter (this guide says `E:`).
- [ ] The file `appraisal-release-1.zip` made on the development machine (section 2), on a USB stick.
- [ ] The **`installers` folder** (four programs, already downloaded and checked) on the same USB stick. Then the PC needs no internet at all.
- [ ] Somewhere safe to keep passwords (the college password safe).

The `installers` folder holds these four free programs, from their official sites:

| Program | File in `installers` | Used in | From |
|---|---|---|---|
| Java 25 (Temurin JRE) | `OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.msi` | step 6.1 | adoptium.net |
| MySQL Installer (installs MySQL 8.4) | `mysql-installer-community-8.0.46.0.msi` (566 MB) | step 6.2 | dev.mysql.com |
| Node.js 22 LTS | `node-v22.23.3-x64.msi` | step 6.3 | nodejs.org |
| NSSM 2.24 | `nssm-2.24.zip` | step 7 | nssm.cc |

Also in the folder: `SHA256SUMS.txt` (to check nothing was damaged).
In the steps below, the `installers` folder on the USB stick is called `D:\installers`: **change `D:` to the real drive letter** of the stick.

### Check the files first (one minute)

Open PowerShell (any window), paste this and look at the right-hand column. Every line must say `OK`:

```powershell
Get-Content D:\installers\SHA256SUMS.txt | ForEach-Object {
  $h, $f = $_ -split '\s+', 2
  $actual = (Get-FileHash "D:\installers\$f" -Algorithm SHA256).Hash.ToLower()
  "{0,-52} {1}" -f $f, $(if ($actual -eq $h) { 'OK' } else { '*** DIFFERENT - do not use this file ***' })
}
```

The MySQL file is large, so this takes a minute. If a file says `DIFFERENT`, copy it from the stick again; if it still differs, do not install it.
## 2. On the development machine: make the release file

Do this once per version, on a Windows machine with internet, Java 25, Maven and Node (the machine you develop on). Not on the server.

```powershell
cd C:\Projects_AI\faculty-apraisal
powershell -ExecutionPolicy Bypass -File scripts\make-windows-release.ps1
```

You should see `Done: ...\release\appraisal-release-1.zip (about 40 MB)` and a SHA-256 line. Copy the zip (and the `.sha256` file next to it) to the USB stick.
The release is numbered: add `-Version 2` for the next one.

If it stops with an error, the message says which part failed (backend build, `npm ci`, or the website build). Fix that and run it again.

## 3. On the server PC: open PowerShell as administrator

You will use this window for everything below.

1. Click **Start**, type `PowerShell`.
2. **Right-click** "Windows PowerShell" and choose **Run as administrator**. Say **Yes**.
3. The title bar must say **Administrator: Windows PowerShell**.

Then, once in this window:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
```

(That only lasts for this window. If you close the window, open a new administrator window and run it again.)

**Pasting:** copy a block, then right-click inside the PowerShell window to paste, then press Enter. Paste one block at a time.

## 4. Make the PC ready

```powershell
powercfg /change standby-timeout-ac 0
powercfg /change hibernate-timeout-ac 0
powercfg /change monitor-timeout-ac 30
New-Item -ItemType Directory -Force C:\FAMS\app, C:\FAMS\nssm, C:\FAMS\data\documents, C:\FAMS\logs, C:\FAMS\secrets | Out-Null
icacls C:\FAMS\secrets /inheritance:r /grant:r "*S-1-5-32-544:(OI)(CI)F" "*S-1-5-18:(OI)(CI)F"
```

You should see `Successfully processed 1 files`. This stops the PC sleeping (a sleeping PC is an unreachable site), creates the folders,
and makes `C:\FAMS\secrets` readable by administrators only.

Then install all Windows updates, restart, and **only then** go on. (Updating in the middle of the season restarts the PC.)

## 5. Fix the PC's address

1. In PowerShell run `ipconfig /all`. Under **Ethernet adapter**, write down: **IPv4 Address**, **Subnet Mask**, **Default Gateway**, **DNS Servers**.
2. **Start > Settings > Network & Internet > Ethernet**, click the network name, scroll to **IP settings**, press **Edit**.
3. Choose **Manual**, switch **IPv4** on, and type the IP address, the subnet prefix length (`255.255.255.0` is `24`), the gateway and the DNS server(s) you wrote down. **Save**.
4. Check it: open PowerShell and run `ipconfig`. The IPv4 address must be the one you typed.

**Write the IP address on paper.** Everything below calls it `<IP>`; the examples use `10.20.30.40`.

If you use a different address from the one DHCP gave you, tell IT first, or two PCs will fight over one address.

## 6. Install Java, MySQL and Node

### 6.1 Java

Double-click `D:\installers\OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.msi`. On the feature page set **"Add to PATH"** and **"Set JAVA_HOME variable"** to *Will be installed*. Finish.
Open a **new** administrator PowerShell window (step 3) and check:

```powershell
java -version
```

It must say `25`. If it says another number or "not recognized", see "Errors" (E1).

### 6.2 MySQL

Double-click `D:\installers\mysql-installer-community-8.0.46.0.msi` (it opens slowly: it is a big file). Choose **Custom**, move **MySQL Server 8.4.x** to the right (not 8.0.x, not 9.x, not Workbench, not Shell).
If no 8.4.x is in the list, **stop** and do not pick another version: the project is built and tested on 8.4 only. Then:

- **Type and Networking:** Config Type **Development Computer** (uses the least memory), port **3306**, **untick** "Open Windows Firewall ports".
- **Authentication:** Use Strong Password Encryption.
- **Accounts:** type a **root password**. Write it in the password safe now. You need it twice below.
- **Windows Service:** name `MySQL84` (leave it), **untick "Start the MySQL Server at System Startup"**, run as Standard System Account.
- **Apply**, until every step has a green tick. Finish. Do not tick "Start MySQL Workbench".

Make it use less memory and stay on this PC. Run this block exactly once:

```powershell
$ini = "C:\ProgramData\MySQL\MySQL Server 8.4\my.ini"
if (-not (Test-Path $ini)) { throw "my.ini not found at $ini - see Errors E4" }
Copy-Item $ini "$ini.bak" -Force
Add-Content -Path $ini -Encoding ASCII -Value @"

[mysqld]
bind-address=127.0.0.1
mysqlx=OFF
innodb_buffer_pool_size=256M
performance_schema=OFF
max_connections=40
log-bin-trust-function-creators=1
"@
Get-Service MySQL*
```

You should see a service named `MySQL84` with Status `Stopped`. The line `log-bin-trust-function-creators=1` is **required**:
without it the database tables cannot be created (E5).

### 6.3 Node.js

Double-click `D:\installers\node-v22.23.3-x64.msi` and accept the default options. Do **not** tick "Automatically install the necessary tools". Then, in a **new** administrator window:

```powershell
node -v
```

It must print `v22...` (or a higher number).

## 7. Put the programs in place

NSSM comes as a zip file. Unpack it (change `D:` to your stick's letter):

```powershell
$i = "D:\installers"
Expand-Archive "$i\nssm-2.24.zip" C:\FAMS\unpack-nssm -Force
Copy-Item C:\FAMS\unpack-nssm\nssm-2.24\win64\nssm.exe C:\FAMS\nssm\ -Force
Remove-Item C:\FAMS\unpack-nssm -Recurse -Force
Test-Path C:\FAMS\nssm\nssm.exe
```

`Test-Path` must print `True`.

Now the application. Put `appraisal-release-1.zip` somewhere you can name, for example `D:\appraisal-release-1.zip` (change the first path to where yours really is):

```powershell
Expand-Archive -Path D:\appraisal-release-1.zip -DestinationPath C:\FAMS\unpack -Force
Copy-Item "$((Get-ChildItem C:\FAMS\unpack -Directory | Select-Object -First 1).FullName)\*" C:\FAMS\app -Recurse -Force
Remove-Item C:\FAMS\unpack -Recurse -Force
Get-Content C:\FAMS\app\VERSION.txt
Test-Path C:\FAMS\app\backend\fams-backend.jar, C:\FAMS\app\frontend\server.js, C:\FAMS\app\scripts\fams-open.ps1
```

`Test-Path` must print `True` three times.

## 8. Make the passwords and settings

Edit the **three values** in the first block (keep the quotes), then paste the whole block. It creates random database passwords and writes two settings files.

```powershell
# ===== EDIT THESE THREE =====
$adminEmail    = "TYPE-THE-ADMIN-EMAIL-HERE"
$adminPassword = "TYPE-A-PASSWORD-HERE"        # 10+ characters, with a letter and a number, letters and digits only
$standard      = "TYPE-THE-STANDARD-PASSWORD-HERE"   # given to every new account; 12+ letters and digits, your own, not a common word
$backupDir     = "E:\fams-backups"             # a folder on the second disk
# ============================

if ($adminEmail -like "TYPE-*" -or $adminPassword -like "TYPE-*" -or $standard -like "TYPE-*") { throw "Type your three values first." }
if ($adminPassword -notmatch '^[A-Za-z0-9]{10,}$' -or $standard -notmatch '^[A-Za-z0-9]{12,}$') { throw "Passwords: letters and digits only, at least 10 (admin) and 12 (standard) characters." }
function New-Pw { $b = New-Object byte[] 18; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ([BitConverter]::ToString($b) -replace '-','').ToLower() }
$app = New-Pw; $mig = New-Pw
New-Item -ItemType Directory -Force $backupDir | Out-Null

@"
FAMS_DB_URL=jdbc:mysql://127.0.0.1:3306/fams?useSSL=false&allowPublicKeyRetrieval=true
FAMS_DB_USER=fams
FAMS_DB_PASSWORD=$app
SPRING_FLYWAY_USER=fams_migrator
SPRING_FLYWAY_PASSWORD=$mig
FAMS_STORAGE_DIR=C:\FAMS\data\documents
FAMS_BACKUP_DIR=$backupDir
FAMS_SECURE_COOKIES=false
FAMS_DEFAULT_PASSWORD=$standard
FAMS_BOOTSTRAP_ADMIN_EMAIL=$adminEmail
FAMS_BOOTSTRAP_ADMIN_PASSWORD=$adminPassword
"@ | Set-Content C:\FAMS\secrets\fams.env -Encoding ASCII

@"
FAMS_DB_URL=jdbc:mysql://127.0.0.1:3306/fams
FAMS_DB_USER=fams_migrator
FAMS_DB_PASSWORD=$mig
FAMS_STORAGE_DIR=C:\FAMS\data\documents
FAMS_BACKUP_DIR=$backupDir
"@ | Set-Content C:\FAMS\secrets\backup.env -Encoding ASCII

@"
CREATE DATABASE IF NOT EXISTS fams;
CREATE USER 'fams_migrator'@'%' IDENTIFIED BY '$mig';
GRANT ALL PRIVILEGES ON fams.* TO 'fams_migrator'@'%';
CREATE USER 'fams'@'%' IDENTIFIED BY '$app';
GRANT SELECT, INSERT, UPDATE, DELETE ON fams.* TO 'fams'@'%';
"@ | Set-Content C:\FAMS\secrets\setup.sql -Encoding ASCII
"Done. Files in C:\FAMS\secrets:"; Get-ChildItem C:\FAMS\secrets | Select-Object Name
```

You should see `backup.env`, `fams.env`, `setup.sql`. **Put a copy of `C:\FAMS\secrets\fams.env` in the college password safe**; it holds the only copy of the database passwords.

## 9. Create the database and its two accounts

```powershell
Start-Service MySQL84
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -u root -p --execute="source C:/FAMS/secrets/setup.sql"
```

It asks for a password: type the **MySQL root password** from step 6.2 (you will not see it as you type). It prints nothing if it worked.
Then delete the file that holds the passwords in plain SQL:

```powershell
Remove-Item C:\FAMS\secrets\setup.sql
Restart-Service MySQL84
```

The restart makes the `my.ini` changes from step 6.2 take effect. If it fails to restart, see E3.

## 10. Check that port 80 is free

The website answers on port 80, so nothing else may use it.

```powershell
Get-NetTCPConnection -LocalPort 80 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress, LocalPort, OwningProcess
```

You should see **nothing**. If a line appears, another program holds port 80: see E10 and free it before going on.

## 11. Turn the programs into Windows services

Windows services start with no one signed in and are restarted if they crash. Paste one block at a time.

**11.1 The backend**

```powershell
$n = "C:\FAMS\nssm\nssm.exe"
$java = (Get-Command java).Source
& $n install FAMS-Backend $java
& $n set FAMS-Backend AppParameters "-XX:+UseSerialGC -Xms64m -Xmx512m -XX:+ExitOnOutOfMemoryError -jar C:\FAMS\app\backend\fams-backend.jar"
& $n set FAMS-Backend AppDirectory C:\FAMS\app\backend
& $n set FAMS-Backend AppStdout C:\FAMS\logs\backend.log
& $n set FAMS-Backend AppStderr C:\FAMS\logs\backend.log
& $n set FAMS-Backend AppRotateFiles 1
& $n set FAMS-Backend AppRotateBytes 10485760
& $n set FAMS-Backend Start SERVICE_DEMAND_START
$envLines = Get-Content C:\FAMS\secrets\fams.env | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' }
& $n set FAMS-Backend AppEnvironmentExtra $envLines
```

**11.2 The website** (it listens on port 80 for the whole college network)

```powershell
$n = "C:\FAMS\nssm\nssm.exe"
$node = (Get-Command node).Source
& $n install FAMS-Frontend $node
& $n set FAMS-Frontend AppParameters "server.js"
& $n set FAMS-Frontend AppDirectory C:\FAMS\app\frontend
& $n set FAMS-Frontend AppStdout C:\FAMS\logs\frontend.log
& $n set FAMS-Frontend AppStderr C:\FAMS\logs\frontend.log
& $n set FAMS-Frontend AppRotateFiles 1
& $n set FAMS-Frontend AppRotateBytes 10485760
& $n set FAMS-Frontend Start SERVICE_DEMAND_START
& $n set FAMS-Frontend AppEnvironmentExtra NODE_ENV=production PORT=80 HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1 BACKEND_URL=http://127.0.0.1:8080 "NODE_OPTIONS=--max-old-space-size=192"
```

**11.3 MySQL starts only when asked** (the open script starts it):

```powershell
Set-Service MySQL84 -StartupType Manual
Stop-Service MySQL84
Get-Service MySQL84, FAMS-Backend, FAMS-Frontend | Format-Table Name, Status, StartType
```

You should see three lines, all `Stopped` and `Manual`.

## 12. Let the college network in (but not yet)

This rule is created **switched off**; `fams-open.ps1` switches them on. By default only PCs on this PC's own network segment can connect.

```powershell
New-NetFirewallRule -DisplayName "FAMS HTTP (college network)"  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 80  -RemoteAddress LocalSubnet -Profile Any -Enabled False | Out-Null
```

If faculty are on other networks (other floors or buildings with their own address range), ask IT for the campus range and widen the
rule (replace `10.0.0.0/8` with IT's range):

```powershell
Set-NetFirewallRule -DisplayName "FAMS HTTP (college network)" -RemoteAddress 10.0.0.0/8
```

Never use `Any`.

## 13. First start

```powershell
powershell -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\fams-open.ps1
```

The first start takes one to four minutes (the database tables are built). You should see the three services start, then
`OPEN. People on the college network can now use the site.`

**Check it, on this PC:**

```powershell
(Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/actuator/health).Content
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 80,8080,3306 } | Sort-Object LocalPort | Format-Table LocalAddress, LocalPort
```

- The first line must print `{"status":"UP"...}`.
- In the second, ports **8080 and 3306 must show `127.0.0.1`**, and only **80 may show `0.0.0.0`** (or `::`). Nothing may listen on 443, 3000 or any other port.

Now open Microsoft Edge or Chrome and go to `http://<IP>` (your IP). You should see the sign-in page. The browser says "Not secure": that is expected.
(The backend log shows a warning that cookies are not marked Secure. That is expected too, because of the plain-HTTP decision.)

**Sign in** with the admin e-mail and password you typed in step 8, change the password (Account), then **remove the one-time settings**:

```powershell
(Get-Content C:\FAMS\secrets\fams.env) | Where-Object { $_ -notmatch '^FAMS_BOOTSTRAP_ADMIN_' } | Set-Content C:\FAMS\secrets\fams.env -Encoding ASCII
$envLines = Get-Content C:\FAMS\secrets\fams.env | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' }
& C:\FAMS\nssm\nssm.exe set FAMS-Backend AppEnvironmentExtra $envLines
Restart-Service FAMS-Backend
```

Then create a **second administrator** in the console (so the site is not lost if one person leaves).

### Tell people the address

Give everyone `http://<IP>` (for example on a notice or by e-mail). **There is nothing to install on their PCs.** Typing `https://` will not work.

## 14. Backups (do this before real data goes in)

**A copy on the same disk is not a backup.** The backup folder is on the second disk.

Make one now and check it:

```powershell
powershell -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\backup.ps1 -To E:\fams-backups -EnvFile C:\FAMS\secrets\backup.env
Get-ChildItem E:\fams-backups
```

You should see a folder like `fams-backup-20261014-193000` and a file `latest.json`. (Change `E:\fams-backups` if you chose another folder in step 8.)

Make one every night while the site is open:

```powershell
schtasks /Create /TN "FAMS nightly backup" /SC DAILY /ST 21:00 /RU SYSTEM /RL HIGHEST /F /TR "powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\backup.ps1 -To E:\fams-backups -EnvFile C:\FAMS\secrets\backup.env"
```


**Restore test (do it once, before the season):** on a *spare* PC set up the same way, run
`powershell -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\restore.ps1 -From E:\fams-backups\<the folder> -EnvFile C:\FAMS\secrets\backup.env`
without `-Overwrite` to check it, and with `-Overwrite` to restore (stop `FAMS-Backend` first). A backup you have never restored is a hope, not a backup.

## 15. The yearly routine

### Before the season (a week before)

1. Install Windows updates and restart. Then **pause updates for 35 days**: Settings > Update & Security > Windows Update > Pause updates for 7 days (repeat up to 35).
2. Plug in the backup disk. Check the IP address is still the same (`ipconfig`).
3. If there is a **new version**: on the development machine make the next release (section 2, with `-Version 2`) and, on the server, take a backup first, then repeat the "copy the application" commands of step 7 **with the services stopped**. (The new version updates the database by itself on the first start and **cannot be undone**; the backup is how you go back.)
4. Open the season:

```powershell
powershell -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\fams-open.ps1
```

5. Open the site, sign in as administrator, and check *System health* in the console.

### After the season

```powershell
powershell -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\fams-close.ps1
```

This shuts the network door, makes a final backup, and stops everything. The data stays on the disk for next year. Unplug the backup
disk and keep it somewhere safe. The PC is then an ordinary PC again.

### Every year

- Open the season **once a month earlier** than you need it, in a quiet hour, so an old PC that fails fails before the rush.
- Keep two backup copies in two places.
- Windows 10 is out of support: if the college can replace this PC with one running Windows 11 or Linux, do so.

## 16. If something goes wrong

Always look first in **`C:\FAMS\logs`**. Show the last lines of a log:

```powershell
Get-Content C:\FAMS\logs\backend.log -Tail 40
```

| # | What you see | What it means and what to do |
|---|---|---|
| E1 | `java -version` says "not recognized", or shows an old number | The PATH is not set. Open a **new** administrator window. If still wrong, reinstall Temurin 25 with "Add to PATH" ticked. Check with `Get-Command java`. If it points to an old Java, uninstall the old one. |
| E2 | Backend log: `UnsupportedClassVersionError` | The service uses an old Java. Run `& C:\FAMS\nssm\nssm.exe set FAMS-Backend Application "<full path to the Java 25 java.exe>"` (find it with `Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Recurse -Filter java.exe`). |
| E3 | `Restart-Service MySQL84` fails, or MySQL will not start | `my.ini` has a mistake. Restore the saved copy: `Copy-Item "C:\ProgramData\MySQL\MySQL Server 8.4\my.ini.bak" "C:\ProgramData\MySQL\MySQL Server 8.4\my.ini" -Force`, start MySQL, and redo step 6.2's block. Look in `C:\ProgramData\MySQL\MySQL Server 8.4\Data\*.err` for the reason. |
| E4 | `my.ini not found` | MySQL is not installed, or is a different version. Check `Get-Service MySQL*` and `C:\ProgramData\MySQL`. |
| E5 | Backend log: `You do not have the SUPER privilege and binary logging is enabled` | The line `log-bin-trust-function-creators=1` is missing from `my.ini`. Add it under `[mysqld]` and restart MySQL. |
| E6 | Backend log: `Access denied for user 'fams'` (or `fams_migrator`) | The password in `fams.env` is not the one MySQL has. Fix MySQL to match the file: `& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -u root -p -e "ALTER USER 'fams'@'%' IDENTIFIED BY '<FAMS_DB_PASSWORD from fams.env>'"` (same for `fams_migrator` with `SPRING_FLYWAY_PASSWORD`). |
| E7 | Backend log: `Communications link failure` / `Connection refused` | MySQL is not running (`Get-Service MySQL84`), or the URL in `fams.env` is wrong. Start MySQL; the open script does this. |
| E8 | Backend log: `FAMS_STORAGE_DIR` / "cannot be written" | The folder `C:\FAMS\data\documents` is missing. Create it and restart the backend. |
| E9 | `FAMS-Frontend` stops straight after starting | Read `C:\FAMS\logs\frontend.log`. Usual causes: `server.js` is missing (redo step 7), or `node` is not Node 22 (step 6.3 and `Get-Command node`). |
| E10 | `frontend.log` shows `EADDRINUSE` or `EACCES` on port 80, or step 10 listed a line | Port 80 is taken. Find who: `netstat -ano \| findstr :80`, then `tasklist /FI "PID eq <the number>"`. IIS: `Stop-Service W3SVC; Set-Service W3SVC -StartupType Disabled`. Skype, VMware, XAMPP and others use this port too. |
| E11 | Browser says "Not secure" | Expected: the site is plain HTTP by decision (section 0). It is not an error. |
| E12 | Browser: "This site can't be reached" when you type `https://...` | There is no HTTPS. Type `http://<IP>`. |
| E13 | Browser on another PC: `ERR_CONNECTION_TIMED_OUT` | (a) The season is closed: run `fams-open.ps1`. (b) That PC is on another network segment: widen the firewall rule (step 12). (c) A network firewall between them: ask IT. |
| E14 | The page opens but sign-in or any later page shows a server error (HTTP 500) | The backend is stopped or still starting. `Get-Service FAMS-*`. Start it, or run `fams-open.ps1`. Check `backend.log`. |
| E15 | The page opens but sign-in does nothing / returns to the sign-in page | `FAMS_SECURE_COOKIES` is still `true` in `C:\FAMS\secrets\fams.env`, so the browser throws the session cookie away on plain HTTP. Set it to `false`, re-apply the settings (the block at the end of step 13) and `Restart-Service FAMS-Backend`. |
| E16 | Backend log: `OutOfMemoryError` and the service keeps restarting | Memory is short. Close other programs. Raise the limit: `& C:\FAMS\nssm\nssm.exe set FAMS-Backend AppParameters "-XX:+UseSerialGC -Xms64m -Xmx768m -XX:+ExitOnOutOfMemoryError -jar C:\FAMS\app\backend\fams-backend.jar"`, restart. Best fix: more RAM. |
| E17 | The PC is very slow with the site open | 4 GB is the limit. Close browsers and Office. Check Task Manager > Memory. Add RAM. |
| E18 | After a restart the site is down | By design: nothing starts by itself. Run `fams-open.ps1`. (To make it start by itself during the season: `Set-Service MySQL84,FAMS-Backend,FAMS-Frontend -StartupType Automatic`. Undo with `Manual`.) |
| E19 | `...fams-open.ps1 cannot be loaded because running scripts is disabled` | Start the command with `powershell -ExecutionPolicy Bypass -File` exactly as written in this guide. |
| E20 | `nssm ... Administrator access is needed` / `Access is denied` | The window is not an administrator window. Redo step 3. |
| E21 | The IP address changed | Nothing in the programs needs changing. Tell everyone the new address. If the new address is on another network segment, check the firewall rule (step 12). |
| E22 | `backup.ps1`: `mysqldump was not found` | MySQL tools are in `C:\Program Files\MySQL\MySQL Server 8.4\bin`; if installed elsewhere add that folder to PATH. |
| E23 | `backup.ps1`: path not found / backup disk missing | The USB drive has another letter. Edit `FAMS_BACKUP_DIR` in both `.env` files and the scheduled task. |
| E24 | `fams-close.ps1` says the final backup failed | Nothing was stopped (on purpose). Fix the cause shown above it and run again, or add `-SkipBackup` if you already have a good backup. |
| E25 | A Windows update restarted the PC during the season | Pause updates for 35 days before opening (section 15). Then run `fams-open.ps1`. |
| E26 | You forgot everyone's passwords | Add `FAMS_RESET_ALL_PASSWORDS=<anything new>` to `fams.env`, re-apply the settings (the block at the end of step 13), restart the backend, then **remove the line**. |

## 17. What was and was not tested

Tested here (on the development PC, Windows):

- The release build: `make-windows-release.ps1` runs end to end and produces the zip (about 42 MB). The step 7 unpack commands
  were run on that zip, and the website was started from the unpacked folder with `node server.js` on `127.0.0.1`: `/login`, the
  image resizer and the static files all answered 200, using about 150 MB of memory. (The test caught one real bug, a PowerShell
  quoting problem in the build script, now fixed.)
- The backend jar builds with `mvn -Dmaven.test.skip=true package`.
- The backup and restore scripts are the project's own, used on this PC for development.
- The four installers in `installers\`: the Java and Node files match their publishers' SHA-256 values, and the three `.msi` files
  (Java, Node, MySQL) carry a valid digital signature (Eclipse Foundation, OpenJS Foundation, Oracle). NSSM publishes no checksum
  and is unsigned: it came from its official site, and `SHA256SUMS.txt` only proves your copy was not damaged afterwards.
- **The built website as the only front door** (run from the release folder, listening on `0.0.0.0` on a spare port, with a stand-in
  backend on port 8080): `/login` answered 200 from the PC's LAN address, `/api/*` reached the backend, no `Server` header was
  sent, a 3 MB upload reached the backend whole, and it used about 80 MB. **It adds no `X-Forwarded-For` and passes a client's own
  value through**, which is why the guide leaves `FAMS_FORWARD_HEADERS` at its default (the backend then ignores the header and
  attributes every request to `127.0.0.1`). The backend's own limit on request size is its protection against large bodies.

**Not tested** (no spare Windows PC with these installs was available): installing MySQL from its installer with the option
choices above (in particular, that this installer offers MySQL **8.4.x** in the Custom list), the NSSM service commands, and the
Windows Firewall rules. Treat the first real installation as the test, with a pencil and the error table. Each of those is a
standard feature; the likely failure is a typing or option mistake, which the table in section 16 covers.

## Appendix A. Switching on HTTPS later (optional)

Only if the college asks for it. HTTPS needs a web server in front of the website, so this adds **Caddy** back (one file, free).
It was tested with Caddy 2.11.7: a certificate for an IP address is issued by Caddy's own authority; a client that trusts only its
`root.crt` accepts it, one that does not refuses it; Caddy's certificates last 12 hours and renew by themselves. Each person's PC
then needs the certificate installed once, or it shows a warning.

1. Download `caddy_..._windows_amd64.zip` from github.com/caddyserver/caddy/releases and unpack `caddy.exe` into `C:\FAMS\caddy\`.
2. Make the website listen on this PC only (Caddy will face the network):

```powershell
& C:\FAMS\nssm\nssm.exe set FAMS-Frontend AppEnvironmentExtra NODE_ENV=production PORT=3000 HOSTNAME=127.0.0.1 NEXT_TELEMETRY_DISABLED=1 BACKEND_URL=http://127.0.0.1:8080 "NODE_OPTIONS=--max-old-space-size=192"
```

3. Write the Caddyfile (your IP in `$ip`):

```powershell
$ip = "10.20.30.40"      # <-- your server's IP address
New-Item -ItemType Directory -Force C:\FAMS\caddy, C:\FAMS\caddy-data | Out-Null
@"
{
	admin off
	storage file_system C:\FAMS\caddy-data
	skip_install_trust
}

http://$ip {
	redir https://$ip{uri} permanent
}

https://$ip {
	tls internal
	request_body {
		max_size 2MB
	}
	header -Server
	handle /api/* {
		reverse_proxy 127.0.0.1:8080 {
			header_up X-Forwarded-For {remote_host}
		}
	}
	handle {
		reverse_proxy 127.0.0.1:3000 {
			header_up X-Forwarded-For {remote_host}
		}
	}
}
"@ | Set-Content C:\FAMS\caddy\Caddyfile -Encoding ASCII
& C:\FAMS\caddy\caddy.exe validate --config C:\FAMS\caddy\Caddyfile --adapter caddyfile
```

4. Make Caddy a service:

```powershell
$n = "C:\FAMS\nssm\nssm.exe"
& $n install FAMS-Caddy C:\FAMS\caddy\caddy.exe
& $n set FAMS-Caddy AppParameters "run --config C:\FAMS\caddy\Caddyfile --adapter caddyfile"
& $n set FAMS-Caddy AppDirectory C:\FAMS\caddy
& $n set FAMS-Caddy AppStdout C:\FAMS\logs\caddy.log
& $n set FAMS-Caddy AppStderr C:\FAMS\logs\caddy.log
& $n set FAMS-Caddy Start SERVICE_DEMAND_START
```

5. In `C:\FAMS\secrets\fams.env` set `FAMS_SECURE_COOKIES=true` and add the line `FAMS_FORWARD_HEADERS=native` (Caddy then
   supplies each person's real address), re-apply the settings (the block at the end of step 13) and `Restart-Service FAMS-Backend`.
6. Firewall: open port 443 with a rule like the one in step 12 (name it `FAMS HTTPS (college network)`), and in `fams-open.ps1` and
   `fams-close.ps1` add `FAMS-Caddy` to the service lists and the HTTPS rule to the firewall lines. Port 80 stays: it redirects.
7. The certificate authority's public file is created at `C:\FAMS\caddy-data\pki\authorities\local\root.crt`. Install it on each PC:
   double-click it, **Install Certificate**, **Local Machine**, **Trusted Root Certification Authorities**. Back up `C:\FAMS\caddy-data`,
   or every PC needs the file again.
8. Give people `https://<IP>` instead.
