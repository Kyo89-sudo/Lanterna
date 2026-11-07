# Lanterna

Player video per Fire TV (testato come target: Fire TV Stick 4K Max) pensato per riprodurre da **SMB senza rebuffering**.

## Perché non si blocca come Kodi/VLC

- **Read-ahead SMB**: il file viene letto in blocchi da 2 MB, fino a 4 richieste in parallelo, e tenuto in RAM in anticipo (32–96 MB). Il player prende i dati dalla memoria invece di fare una richiesta di rete ogni pochi KB.
- **Buffer del player grande**: fino a 10 minuti o 64–192 MB (impostabile).
- **Decodifica solo hardware per prima**: AV1, H.265 10 bit e H.264 vanno sui decoder della Fire TV, mai in software se c'è quello hardware.
- **Riconnessione automatica**: se la connessione SMB cade, riapre e riprende dalla stessa posizione (fino a 10 tentativi).

## Funzioni

- Più server SMB2/3 con utente e password (o guest)
- Navigazione cartelle col telecomando
- Ripresa dal punto in cui avevi lasciato
- Tracce audio e sottotitoli interni (SRT, ASS/SSA, PGS) + sottotitoli esterni con lo stesso nome del file (`Film.srt`, `Film.it.srt`, …)
- Audio EAC3/AC3 (anche Atmos) in passthrough o decodificato dalla Fire TV
- Pannello info con decoder in uso, buffer, velocità SMB e numero di rebuffering
- Test velocità SMB su un file

## Telecomando nel player

| Tasto | Azione |
|---|---|
| OK | Pausa / play |
| ◀ ▶ | −10 s / +10 s (tenendo premuto: 30 s) |
| ▲ ▼ | Mostra i controlli (audio, sottotitoli, velocità) |
| ⏪ ⏩ ⏯ | Tasti multimediali |
| ≡ (menu) | Pannello info |
| Indietro | Chiude i controlli, poi esce |

Nelle liste: **tieni premuto OK** o premi **≡** per le opzioni (modifica server, test velocità, riproduci dall'inizio).

## Come ottenere l'APK

### Opzione A: GitHub (nessun programma da installare)

1. Crea un repository su GitHub e carica il contenuto di questa cartella.
2. La build parte da sola (scheda **Actions** → *Build APK*).
3. Al termine scarica l'artifact **Lanterna-apk** e scompattalo: dentro c'è `app-release.apk`.

### Opzione B: Android Studio

1. Apri la cartella `Lanterna` con Android Studio.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. L'APK è in `app/build/outputs/apk/`.

L'APK è sempre firmato con la stessa chiave (`keystore/lanterna.jks`), quindi gli aggiornamenti si installano sopra la versione precedente senza perdere server e posizioni salvate.

## Installazione sulla Fire TV

- **Downloader**: carica l'APK su un link diretto e aprilo con l'app Downloader.
- **adb**: abilita *Impostazioni → La mia Fire TV → Opzioni sviluppatore → Debug ADB*, poi:
  ```
  adb connect IP_DELLA_FIRESTICK
  adb install -r app-release.apk
  ```

## Primo avvio

*Aggiungi server* → indirizzo IP del PC/NAS, nome della cartella condivisa, utente e password. Se un video fa ancora rebuffering, premi **≡** durante la riproduzione: il pannello mostra se il decoder è hardware e quanto veloce arriva la rete.

## Limiti noti

- DTS e TrueHD funzionano solo in passthrough verso un ampli/soundbar che li supporta (nessun decoder software incluso).
- I sottotitoli ASS vengono mostrati con stile semplificato.
- La password è salvata nei dati privati dell'app, non cifrata.
