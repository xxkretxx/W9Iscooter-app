# W9 Scooter

Własna aplikacja Android do hulajnogi **iScooter W9** (i innych hulajnóg obsługiwanych przez aplikację **Voltix**, usługa BLE `FF60`).

## Funkcje
- Licznik prędkości, bateria + szacowany zasięg, napięcie / prąd / moc / temperatura, kody błędów
- Bieg (tryb prędkości), blokada z PIN-em, światło, tempomat, start od zera, limit prędkości, hamulec elektroniczny, jasność, jednostki
- Automatyczne łączenie z ostatnią hulajnogą
- Nagrywanie tras GPS (działa przy zgaszonym ekranie), historia przejazdów z mapą (OpenStreetMap), statystyki
- Alarm prędkości (wibracja), tryb „nie wygaszaj ekranu”
- **Analiza (czego nie ma w Voltix):**
  - wykresy na żywo: moc, natężenie prądu, napięcie, prędkość (odświeżanie 4×/s)
  - statystyki sesji: Wh zużyte / odzyskane, Wh/km, szczyty mocy i prądu
  - BMS: napięcie pakietu i średnie na ogniwo, SoC liczony z napięcia, opór wewnętrzny pakietu, spadek napięcia pod obciążeniem, napięcia ogniw (jeśli BMS je wysyła), surowe dane
  - test przyspieszenia 0–20/25/30/40 km/h
- Strojenie rekuperacji (KERS) 0–5 z licznikiem odzyskanej energii
- Profile jazdy (Eko / Miasto / Sport / Własny) – jednym kliknięciem
- Auto-światło (czujnik światła telefonu), alerty niskiej baterii
- Eksport tras do GPX (Strava, Komoot), przypomnienia serwisowe wg przebiegu
- Konsola BLE – podgląd ramek i wysyłanie własnych komend (komendy bind/reset/OTA są zablokowane)

## Pobieranie APK
Każdy push buduje APK przez GitHub Actions:
- zakładka **Releases** → najnowszy `W9Scooter-N.apk`, albo
- **Actions** → ostatni build → artefakt `W9Scooter-apk`.

APK jest podpisane stałym kluczem (`app/w9.keystore`), więc nowe wersje instalują się jako aktualizacja.

## Protokół
Patrz [PROTOCOL.md](PROTOCOL.md) – na podstawie [duketopceo/scooter-ios](https://github.com/duketopceo/scooter-ios) (MIT).
Potwierdzone: połączenie, telemetria, bieg. Pozostałe komendy mogą wymagać dopasowania do W9 – użyj konsoli BLE.
