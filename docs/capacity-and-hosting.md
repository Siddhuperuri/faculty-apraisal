# Capacity and hosting estimate: 300 to 500 faculty, proof uploads kept for 3 years

Prepared 8 October 2026. This is an estimate, not a quote. What was **measured on this system**, what was **assumed**
and what was **found on the web** is marked each time; prices change, so get a current quote before buying anything.

## 1. The short answer

| | 300 faculty | 500 faculty (upper limit) |
|---|---|---|
| **Server** | 2 vCPU / 4 GB RAM is enough | **4 vCPU / 8 GB RAM** gives comfortable headroom |
| **Disk for the app and uploads** | 256 GB SSD | **512 GB SSD** |
| **Disk for backups** (separate drive) | 1 TB | 1 to 2 TB |
| **Proof files after 3 years** (typical case) | about 17 GB | about 28 GB |
| **Proof files after 3 years** (worst realistic case, no limits on file size) | about 133 GB | about 222 GB |
| **Cheapest sensible way to host** | a spare or new office PC on the college network with a UPS: **about ₹0.3 to 0.9 lakh over 3 years** | same machine |
| **Cloud, if wanted** | about ₹0.4 to 1.0 lakh over 3 years (India VPS or AWS Lightsail Mumbai) | about ₹1.0 to 1.9 lakh |

**What decides the size is the uploads, not the computer.** The people, the forms and the database are tiny. The one
decision that moves the cost by ten times is **how large an uploaded file may be**. Phone photos of certificates are 3
to 6 MB each; the same certificate as a compressed scan or a first-page PDF is 0.2 to 0.5 MB. If the system limits each
file to 2 MB and shrinks photos on upload, the 3-year total for 500 faculty is closer to 5 to 30 GB than 220 GB.

## 2. What is already known (measured here)

- A completely filled-in appraisal printed as the draft PDF is **87 KB**. The official copy stored at approval is the
  same size, so 500 faculty over 3 years add about **0.13 GB**. The draft you mention is generated on the fly and stores nothing.
- The database for the three demo appraisals is **1.4 MB** including indexes. A real appraisal is a few hundred KB at most,
  so 500 faculty over 3 years, with the audit trail, is **about 1 GB**.
- The application already keeps files in one private folder (`FAMS_STORAGE_DIR`), checks them against a checksum, and the
  backup and restore scripts copy that folder together with the database. Uploads were removed from the application
  earlier at the college's request (migration V15); bringing them back is development work (see section 7).

## 3. How much space the uploads need (assumptions)

The college has not said how many files people will upload, so these are **assumed** and meant to be replaced by real
numbers (see section 8). Files per faculty member per year, from the sections you listed:

| Section | What is uploaded | Light | Typical | Heavy |
|---|---|---|---|---|
| 4 FDPs and certifications | programme + certificate (2 files per FDP) | 2 | 6 | 14 |
| 5 Administration | proof of the role | 0 | 1 | 3 |
| 6 Research and publications | first page of each paper (and DOI as text) | 1 | 3 | 8 |
| 7 Funded projects and consultancy | sanction letter | 0 | 0.5 | 3 |
| 8 Patents, books and IPR | proof | 0 | 0.5 | 3 |
| 9 Outreach | proof | 1 | 2 | 5 |
| 10 Memberships, awards | proof | 2 | 3 | 6 |
| **Files per person per year** | | **6** | **16** | **42** |

Average size of one file: **0.4 MB** if the system limits and compresses files, **1 MB** for a mix of scans and photos
with a 2 MB limit, **3 MB** if people can upload raw phone photos and whole papers. Add **20 %** for files uploaded again
after being rejected ("approval pending" means some are replaced).

Space needed at the end of year 3 (the highest point, just before the hard delete):

| Case | Per person per year | 300 faculty | 500 faculty |
|---|---|---|---|
| Light (6 files × 0.4 MB, enforced) | 2.9 MB | 2.5 GB | 4.2 GB |
| **Typical (16 files × 1 MB)** | **19 MB** | **17 GB** | **28 GB** |
| Heavy (42 files × 3 MB, no limits) | 151 MB | 133 GB | 222 GB |

Formula, to redo it with real numbers: `faculty × files per year × average MB × 1.2 × 3 years`.

Add to that about 1.5 GB for the database and issued reports, and about 40 GB for the operating system, the application
and logs. **Backups** need a separate drive of about **2.5 times the data** (a few nightly copies), so 1 TB covers the
heavy case for 300 faculty and 2 TB covers it for 500. Files already compressed (JPEG, PDF) do not shrink further.

## 4. The computer

500 people do not make a busy system. Planning on the busiest day of the submission window: perhaps 150 people use it
that day, about 60 at the same time in the busiest hour, each saving every few seconds. That is a few requests a second
at most; a small server handles hundreds. Uploads (2 MB each) and the draft PDF (a fraction of a second of work) are light.

| Part | Minimum (300 faculty) | Recommended (500 faculty) | Why |
|---|---|---|---|
| CPU | 2 cores | **4 cores** | headroom for the submission rush and PDF/image work |
| RAM | 4 GB (Linux) | **8 GB** (16 GB if Windows or virus scanning) | database ~1.5 GB, Java backend ~1 GB, website ~0.5 GB, operating system 1 to 3 GB; a virus scanner (ClamAV) alone needs about 1.5 GB |
| Disk | 256 GB SSD | **512 GB SSD** | section 3 |
| Backup disk | 1 TB | **2 TB**, a separate physical drive | a copy on the same disk is not a backup |
| Network | 100 Mbps | 1 Gbps on the college LAN | |
| Power | UPS 600 VA | **UPS 1 kVA** | a power cut in the middle of a database write is the commonest way to lose data |

The application needs **no internet** while it runs, so a college-network server keeps working when the ISP does not.

## 5. Where to run it, with costs

Prices are in rupees at roughly **₹96 to the US dollar** (the 2026 range found online was 86 to 97, so check the day's
rate); cloud prices are **before GST**, which is normally 18 % extra on Indian invoices (verify with the provider).

### A. On the college network (recommended for the budget)

| Option | One-time | Per year | 3-year total |
|---|---|---|---|
| **A1. Reuse a spare office PC** that meets section 4 (add a backup disk and UPS) | ₹10,000 to 15,000 | ₹7,000 (electricity, 100 W always on) | **about ₹0.3 to 0.4 lakh** |
| **A2. New office desktop**, i5-class, 16 GB, 512 GB SSD, plus UPS and 2 TB backup disk | ₹45,000 to 70,000 | ₹7,000 | **about ₹0.7 to 0.9 lakh** |
| **A3. Entry tower server** (such as the Dell PowerEdge T150 or T160; Dell India lists these from about ₹83,000 to ₹1.28 lakh; the listed specifications were not confirmed), with UPS and backup disk | ₹1.0 to 1.5 lakh | ₹10,000 | **about ₹1.3 to 1.8 lakh** |

The hardware prices in A1 and A2 are my rough estimates, not quotes. A3 buys error-correcting memory, a 3-year on-site
warranty and remote management, which only matter if nobody can reach the machine quickly when it fails.

### B. Cloud

| Option | What it is | Monthly | 3-year total (before GST) | Notes |
|---|---|---|---|---|
| **AWS Lightsail, Mumbai**, 2 vCPU / 4 GB / 80 GB | virtual server | $24 ≈ ₹2,300 | **≈ ₹0.83 lakh** | fits the typical case; the Mumbai plans include half the usual data transfer (about 2 TB, ample) |
| AWS Lightsail, Mumbai, 2 vCPU / 8 GB / 160 GB | virtual server | $44 ≈ ₹4,200 | ≈ ₹1.5 lakh, ≈ ₹1.9 lakh in the heavy case | extra disk is $0.10 per GB-month (the heavy case needs about $10 more) |
| DigitalOcean, Bangalore, 4 GB or 8 GB | virtual server | $24 to $48 | ≈ ₹0.83 to 1.7 lakh | billed in dollars by card, no UPI; backups add 20 % |
| **Indian VPS providers** (E2E Networks, Inservers, Netspace and others) | virtual server | from ₹900 to ₹2,500 | **≈ ₹0.3 to 0.9 lakh** | rupee invoices, UPI. The ₹880 plan found (2 vCPU / 4 GB / 40 GB NVMe) is from a competitor's listing: ask each provider for a written quote |
| Oracle Cloud "Always Free", Mumbai or Hyderabad | free virtual server | ₹0 | ₹0 | reported cut in mid-2026 to 2 cores / 12 GB / 200 GB; free servers may be unavailable or reclaimed; sign-up needs an international card (RuPay often fails). Fine for a trial, **not for 3 years of records** |
| Hetzner (Germany, Finland, US, Singapore) | virtual server | about €6 to €36, uncertain | | cheapest, but no Indian data centre, prices rose sharply in 2026 and cheap plans were reported unavailable |

**Storing the files separately** (needs a change in the application, which now writes to a folder on the server):

| Service | Price found | 28 GB (typical) | 222 GB (heavy) |
|---|---|---|---|
| Backblaze B2 | $6.95 per TB-month, downloads free up to 3× stored | about ₹20 a month | about ₹150 |
| Cloudflare R2 | $0.015 per GB-month, no download fee, 10 GB free | about ₹25 | about ₹320 |
| Amazon S3 Mumbai | about ₹2.1 per GB-month (reseller price) | about ₹60 | about ₹470 |
| Indian object storage (E2E, Netspace) | ₹0.5 to 3 per GB-month | ₹15 to 85 | ₹110 to 670 |

Cloud storage is very cheap; what costs money in the cloud is the always-on server. Backblaze B2 or an Indian provider is
the cheapest place for an **off-site backup copy** (about ₹20 to 150 a month) whichever way the main system is hosted.

### Comparison over the 3-year cycle

| | 3-year cost | What you take on |
|---|---|---|
| A1 spare PC on the college network | **₹0.3 to 0.4 lakh** | needs power backup, a locked room, someone to run backups |
| A2 new desktop | ₹0.7 to 0.9 lakh | same |
| Indian VPS | ₹0.3 to 0.9 lakh + GST | internet dependence, public exposure (below) |
| AWS Lightsail Mumbai | ₹0.8 to 1.9 lakh + GST | dollar billing, public exposure |
| A3 tower server | ₹1.3 to 1.8 lakh | the most reliable hardware |

## 6. Things that matter more than the price

- **The system was designed for the college network only.** In the cloud it is on the internet. Mitigate by allowing
  connections only from the college's public address (if the college has a fixed one) or by a VPN, by using HTTPS, and
  by keeping the database unreachable from outside. If the college has neither, hosting on the college network is the
  safer choice. Students share the network, so the account protections already built stay on in both cases.
- **Backups and the 3-year delete must agree.** A hard delete that leaves every file in last month's backup has not
  deleted it. Keep backups for a short, fixed time and delete or overwrite them with the same schedule.
- **Data protection law.** India's DPDP rules (final text November 2025, main duties apply from about May 2027) do not,
  as far as the sources found say, require educational institutions to host in India, but the college stays responsible for
  what a cloud provider does with the data. Choosing a Mumbai or Hyderabad region keeps this simple. This is research, not legal advice.
- **One machine is one point of failure.** At this size that is acceptable if backups are tested (the restore script
  exists and should be tried once before go-live) and a spare disk or PC can be put in service within a day.
- **Staff time** to patch, watch disk space and check backups is the cost that neither table includes.

## 7. What it takes to add the uploads (so the estimate matches the build)

- Change the request size limit (now 1 MB for every request) for the upload route only, in the application and in any
  web server in front of it; reject files by type (PDF, JPEG) and by size, and **compress or resize photos on upload**,
  which is the cheapest saving available.
- Keep the 3-year rule in one scheduled job that deletes the files and their records, and make backups expire on the same
  clock.
- Decide who approves a file ("approval pending for all uploads": the Head of the Department?) and what happens to a rejected one (delete it at once to free the space).
- Optional: scan uploads for viruses (adds about 1.5 GB of RAM).
- The Administration change you listed (replace the period with "present in the assessment period or not") is a form field
  change; it adds no storage.

## 8. Questions that would sharpen the numbers

1. How many files did 15 to 20 faculty (a mix of cadres) actually have as proof last year, and how large were they?
2. Will every entry need a proof, or only some? (Every entry would raise the counts.)
3. Is there a spare machine, a room with power backup, and a person to look after it?
4. Does the college have a fixed public internet address? (decides whether the cloud can be kept college-only)
5. Who approves an upload, and are rejected ones deleted at once?

## Sources

Cloud and hardware figures come from provider pages and third-party price guides found on 8 October 2026; where a page
was a reseller's or a blog's, that is said above.
[AWS Lightsail pricing](https://aws.amazon.com/lightsail/pricing) ·
[Lightsail 2026 guide](https://cloudburn.io/blog/amazon-lightsail-pricing) ·
[Amazon S3 in India (reseller)](https://precisiontech.in/cloud/amazon-aws-cloud/aws-pricing/) ·
[Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/) ·
[Backblaze B2 pricing](https://www.backblaze.com/cloud-storage/pricing) ·
[Oracle Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm) ·
[Oracle halves free Ampere A1](https://linuxiac.com/oracle-quietly-cuts-free-tier-ampere-a1-resources-in-half/) ·
[DigitalOcean Bangalore region](https://www.digitalocean.com/company/blog/introducing-our-bangalore-region-blr1/) ·
[Hetzner 2026 price increase](https://findstack.com/resources/hetzner-price-increase-2026) ·
[E2E cost guide](https://www.e2enetworks.com/cloud-terms/cloud-computing-dictionary/cloud-cost-india) ·
[Indian VPS comparison (Inservers)](https://gbnodes.host/blogs/e2e-networks-alternative-india-2026/) ·
[Dell India servers](https://dell.com/en-in/shop/title/sr/enterprise-products) ·
[DPDP Rules 2025 and data transfers](https://sflc.in/dpdp-rules-2025-significant-data-fiduciaries-and-data-transfers/) ·
[USD/INR](https://pluang.com/en/tools/currency-converter/usd-inr)
