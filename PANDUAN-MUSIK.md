# Panduan Musik X FIVE LIVE (Android, Zalith Launcher)

Panduan ini untuk memasukkan lagu pribadi secara manual, tanpa Spotify atau YouTube.

## Langkah 1: Buka folder game di Zalith

1. Buka aplikasi **Zalith Launcher**.
2. Pilih instance Minecraft yang sudah ada mod X FIVE LIVE-nya.
3. Cari pengaturan folder game (sering disebut **Game directory** atau **Folder game**).
   Lokasinya bisa berbeda di tiap versi Zalith. Kalau tidak ketemu, cek pengaturan instance dan pengaturan umum Zalith.

## Langkah 2: Buka folder xfive-music

1. Buka game sekali dengan mod ini. Folder **xfive-music** akan dibuat otomatis di dalam folder game.
2. Keluar dari game, lalu buka aplikasi **File** (atau File Manager) di HP.
3. Masuk ke folder game dari Langkah 1, lalu buka folder **xfive-music**.

Kalau folder itu belum ada, buat sendiri dengan nama persis `xfive-music` (huruf kecil semua).

## Langkah 3: Salin lagu ke dalam folder

1. Salin file lagu ke dalam folder **xfive-music**.
2. Format yang didukung: **.mp3**, **.m4a**, **.ogg**, dan **.wav**.
3. Beri nama file dengan pola **Artis - Judul.mp3**, misalnya `Adele - Hello.mp3`.
   Dengan pola ini, nama artis dan judul tampil di HUD.
4. Lirik: kalau tidak ada file lirik, mod mengunduh lirik bersinkron **otomatis** dari lrclib.net saat lagu diputar
   (perlu internet; hasilnya disimpan di folder `xfive-lirik`). Bisa dimatikan di **Settings > Auto-download lyrics**.
   Kalau mau lirik sendiri, taruh file **Artis - Judul.lrc** di folder yang sama dengan lagu atau di `xfive-lirik`.
5. Tampilan lirik diatur di halaman **Custom HUD**: animasi baris (Fade, Rise, Drop, Pop, Typewriter, Words, Wave),
   sorot karaoke, baris sebelumnya/berikutnya, titik hitung mundur saat musik instrumental (**Instrumental dots**), posisi, dan **Lyrics sync** untuk menggeser waktu kalau lirik terlalu cepat/lambat.

### Cara memasukkan lagu dari file ZIP

1. Unduh lagu dalam bentuk ZIP seperti biasa.
2. Buka aplikasi **File**, lalu ekstrak (unzip) file ZIP tersebut. Hasilnya berupa file .mp3, .m4a, .ogg, atau .wav.
3. Salin file-file lagu itu ke folder **xfive-music** (lihat Langkah 2).
4. Buka game, lalu tekan **Right Shift**. Daftar lagu diperbarui otomatis setiap menu dibuka, jadi **tidak perlu restart atau muat ulang Minecraft**.
5. Di menu, perhatikan baris **Folder lagu** dan **Jumlah lagu**. Baris itu menampilkan lokasi folder yang sedang dipakai dan berapa lagu yang terbaca.

Mod tidak bisa membuka pemilih file dari HP. Karena itu lagu harus disalin manual ke folder yang ditampilkan di menu.

## Langkah 4: Putar lagu di dalam game

1. Buka game, lalu tekan **F6** untuk membuka Control Center di halaman Audio.
2. Lagu baru otomatis terbaca. Tidak perlu restart atau muat ulang Minecraft.
3. Ketuk sebuah lagu untuk memutar, atau tekan tombol putar di bar bawah.
4. Atur volume dengan slider di bar bawah. Tombol panah pindah ke lagu sebelumnya atau berikutnya.
5. Tekan **Close** untuk keluar. Pengaturan tersimpan otomatis.

## Control Center (semua dalam satu jendela)

Tekan **F6** untuk langsung ke **Now Playing**, atau **Right Shift** untuk **Home**. Mouse otomatis aktif saat jendela terbuka. Tampilannya kaca gelap: menu di kiri, isi di tengah, bar pemutar di bawah.

Menu kiri:
- **Kotak cari (paling atas)**: ketik judul lagu atau artis (minimal 2 huruf), berhenti mengetik sebentar, lalu hasilnya muncul di halaman **Search**: lagu di HP Anda dan lagu online. Ikon kaca di kiri kotak = cari sekarang.
- **Home**: sapaan, tombol **Play hits** (memutar tangga Top Indonesia dari lagu pertama), dan kartu tangga 12 negara.
- **Charts**: pilih negara di baris tombol, daftar 50 lagu terpopuler muncul. Ketuk lagu untuk memutar; setelah selesai lanjut otomatis ke lagu berikutnya.
- **Now Playing**: lagu di Library (folder musik Anda) dan lagu online yang sedang diputar. Kolom di atas daftar: isi path folder atau file lalu **Add**, **Refresh** untuk memindai ulang.
- **Liked Songs**: semua lagu yang Anda beri hati (lagu Library dan lagu online).
- **Settings** (tombol bawah): tab **General**, **HUD**, **Controls**, **Appearance**, **Specs**, **Sources**.
- Nama Anda di bawah: ketuk untuk membuka tab Sources.

Di kanan atas tiap daftar: kotak **Filter this list...** dan tombol **List / Covers** (daftar atau kotak sampul warna). Geser dengan jari untuk menggulir.

**Bar pemutar (bawah)**: sampul, judul, artis, ulang, sebelumnya, play/pause, berikutnya, acak, hati (suka lagu yang sedang diputar), volume, dan garis progres (ketuk atau geser).

**Settings**
- **General**: aktifkan mod, putar otomatis, unduh lirik otomatis (dari lrclib), baca spesifikasi perangkat, pindai ulang folder.
- **HUD**: nyalakan/matikan tiap HUD, animasi lirik, posisi lirik, sinkron lirik, opacity, lebar/tinggi Dynamic Island. **Move HUDs (drag)** membuka mode geser: tahan dan geser kotak HUD dengan jari.
- **Controls**: bentuk Island (Card/Pill), warna, Auto hide, lama pop-up, tampil saat jeda, repeat, shuffle.
- **Appearance**: 8 warna tema ditambah **warna sendiri**: ketuk kotak warna terakhir lalu atur **Hue** (pelangi), **Saturation**, **Brightness**. Warna dipakai di seluruh Control Center dan semua HUD. Juga ada gaya UI (Liquid Glass Lite / Dark Music), font, Panel opacity dan Spring animations.
- **Specs**: spesifikasi perangkat dengan animasi (ketuk untuk memutar ulang).
- **Sources**: ringkasan sumber lagu.

Tekan tombol **X** di kanan atas atau Esc untuk keluar. Pengaturan tersimpan otomatis.

## Dynamic Island

Saat lagu diputar, kapsul kecil muncul di atas layar, membuka menjadi kartu, dan menutup kembali saat musik berhenti. Di **Custom Controls**, nyalakan **Auto hide pop-up** agar kartu hanya muncul beberapa detik setiap lagu mulai atau berganti. Atur lamanya dengan **Pop-up time**.

## Opsional: lagu dari folder lain

Kalau lagu ada di folder lain di HP (misalnya Music atau Download):

1. Buka **xfive-music**, lalu buat file teks bernama **folder-musik.txt**.
   File ini dibuat otomatis saat game dibuka, jadi cukup dibuka dan diedit.
2. Tulis satu path per baris. Contoh:
   ```
   /storage/emulated/0/Music
   /storage/emulated/0/Download/lagu.mp3
   ```
3. Baris yang diawali tanda **#** diabaikan.
4. Simpan, lalu buka game dan tekan **Right Shift**. Lagu dari folder tambahan akan terbaca.

Catatan: cara paling mudah dan paling pasti adalah menyalin lagu langsung ke folder **xfive-music**.

## Kalau musik tidak berbunyi

- Pastikan formatnya benar-benar MP3, M4A (AAC), OGG, atau WAV. Mengganti nama file .webm menjadi .mp3 tidak membuat file itu jadi MP3. M4A berisi Apple Lossless (ALAC) dan file AAC mentah (.aac) belum didukung; ubah dengan aplikasi konverter ke M4A (AAC) atau MP3.
- Kalau status menulis "tidak berisi audio", file-nya kemungkinan rusak atau tidak lengkap. Coba salin ulang dari sumbernya.
- Coba dulu dengan lagu pendek (1 sampai 3 menit). Lagu sangat panjang butuh memori besar.
- Lihat tulisan status di menu X FIVE LIVE. Di sana tertulis alasannya, misalnya "Format tidak didukung" atau "Belum ada lagu".
- Baris **Status** di menu (Right Shift) menampilkan kondisi terakhir. Jika tertulis "Gagal", pesan setelahnya menjelaskan penyebabnya.
- Cek volume di tiga tempat:
  1. Volume HP (tombol volume samping, dan pastikan tidak dalam mode senyap).
  2. Di Minecraft, buka **Options → Music & Sounds** dan pastikan **Master Volume** tidak nol.
  3. Slider **Volume** di menu X FIVE LIVE.

## Batasan saat ini

- Spotify dan YouTube Music tidak bisa diputar di dalam mod (lagunya dilindungi hak cipta). Musik berasal dari file di HP atau dari lagu online (Audius dan cuplikan Apple, lihat bagian "Lagu online").
- HUD di dalam game tidak bisa diklik (kursor terkunci). Tekan F6 untuk membuka Control Center dan mengendalikan musik.
- Pemutaran di Android belum diuji langsung di perangkat. Kalau tidak berbunyi, kirim status yang muncul di menu.

## Halaman Specs

Di menu samping ada halaman **Specs**: spesifikasi HP (Infinix GT30 Pro, Dimensity 8350 Ultimate, RAM 8 GB / ROM 256 GB, GPU Mali, XOS 16) dengan animasi: HP bergaris tergambar, chip membal, judul berganti huruf acak, lalu baris spesifikasi diketik satu per satu. Ketuk halamannya untuk memutar ulang animasi.

## Lagu online (tanpa download)

Fitur download sudah dihapus. Semua lagu online **diputar langsung**, tidak disimpan ke Library. Perlu internet.

- **Charts / Home**: tangga lagu terpopuler 12 negara (Indonesia, USA, UK, Korea, Jepang, India, Brasil, Malaysia, Filipina, Thailand, Jerman, Prancis), dari tangga Apple Music "Most Played". Hanya daftar judul dan artis; audionya dicari saat Anda memutar.
- **Search**: mencari di Audius (lagu **lengkap**, gratis) dan di katalog Apple (**cuplikan 30 detik**, dipakai kalau lagunya tidak ada di Audius).
- Untuk lagu tangga: mod mencari versi lengkap di Audius dulu. Kalau tidak ada, memutar cuplikan 30 detik dari Apple (ditandai di pemutar). Kalau dua-duanya tidak ada, lagu dilewati.
- **Antrean otomatis**: lagu dari daftar (tangga, hasil cari, Liked Songs) lanjut ke lagu berikutnya sampai habis. Next/Previous, Shuffle dan Repeat bekerja pada antrean itu.
- Lagu dimuat dulu ke folder sementara `xfive-cache` (biasanya beberapa detik), lalu diputar. Folder itu dikosongkan setiap game dibuka. Ini bukan streaming sungguhan: lagu harus selesai dimuat dulu.
- **Hati**: ketuk hati di baris lagu atau di bar pemutar untuk menyimpan ke **Liked Songs**. Lagu online favorit tersimpan sebagai judul + alamat, jadi tidak ada file yang disimpan dan tetap ada setelah keluar game.
- Lirik diunduh otomatis saat lagunya diputar (kalau "Auto-download lyrics" aktif).

**Jujur soal katalog**: katalog gratis tidak sama dengan Spotify. Banyak hits label besar tidak ada versi lengkapnya; untuk itu yang bisa diputar hanya cuplikan 30 detik.

**Favorit hilang setelah keluar game (sudah diperbaiki)**: dulu daftar favorit disimpan dengan pemisah yang salah, sehingga saat dibuka lagi beberapa favorit tergabung jadi satu dan hilang dari daftar. Sekarang disimpan dengan benar, dan file lama tetap terbaca.

## Spesifikasi perangkat otomatis

Halaman **Specs** kini membaca spesifikasi HP/perangkat yang sedang dipakai (nama perangkat, chipset, RAM / penyimpanan, GPU, sistem). Matikan lewat Settings → **Specs: read my device** untuk kembali ke preset Infinix GT30 Pro.
- RAM dan penyimpanan dibulatkan ke ukuran yang dijual (misalnya terbaca 7,4 GB → "8 GB").
- Bagian yang tidak bisa dibaca ditulis **Unknown**, tidak ditebak. Kode chipset MediaTek yang belum dikenal ditulis apa adanya (misalnya "MediaTek MT6999"); khusus Infinix GT30 Pro dipakai nama resmi "Dimensity 8350 Ultimate".
- Belum dicoba di HP: cara pembacaannya bergantung pada izin Android di Zalith. Kalau gagal, halaman otomatis memakai preset.

Catatan jujur: semua ini sudah dites bagian logikanya di komputer dan terbukti terkompilasi di GitHub, tapi tampilannya belum dicoba langsung di HP.
