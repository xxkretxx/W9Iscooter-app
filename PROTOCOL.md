# Protokół BLE Voltix (iScooter W9) – odtworzony z Voltix 1.1.1 (com.world.voltix)

Usługa `FF60`, notyfikacje `FF61`, zapis `FF62`. MTU 512.

Ramka: `1A A1 | CMD | PARAM | LEN | PAYLOAD… | CRC_lo CRC_hi | 1F F1`
CRC-16/MODBUS (poly 0xA001, init 0xFFFF) po bajtach CMD..PAYLOAD, zawsze 2 bajty, młodszy pierwszy.
PARAM: 1 = zapytanie, 2 = ustawienie.

## Połączenie (jak Voltix)
1. `30 02 [01]` connect (2× co ~500 ms)
2. `10 01 []` heartbeat co 1 s
3. Hulajnoga **sama** wysyła ramki `0x20` (Meter1) i `0x21` (Meter2). `0x60` nie jest używane.

## Komendy (PARAM = 2)
| CMD | Payload | Znaczenie |
|---|---|---|
| 0x32 | `[00]` / `[00 d1 d2 d3 d4]` | blokada – przełącznik (z PIN-em gdy ustawiony) |
| 0x33 | `[00 FA 00 00 00 00]` / `[00×6]` | przednie światło ON / OFF |
| 0x35 | `[tryb, 00]` | tryb: 0 spacer, 1 eko, 2 normal, 3 sport (4,5 sport2/3) |
| 0x36 | `[km/h]` | limit prędkości (zakres z 0x62), tylko na postoju |
| 0x37 | `[0..3]` | KERS: 0 adaptacyjny, 1 słaby, 2 średni, 3 mocny |
| 0x38 | `[05 03]` / `[00 03]` | tempomat ON / OFF |
| 0x39 | `[01 00]` / `[00 00]` | zero start ON / OFF |
| 0x3B | `[0/1]` | napęd 1 / 2 silniki |
| 0x3C | `[0/1]` | jednostki km/h / mph |
| 0x47 | `[0/1]` | smart lock |

Zapytania (PARAM = 1): `61` serial, `62` limit (odp. [min, max]), `63` światła, `64 [01]` ogniwa, `65 [01]` BMS info, `66 [t]` wersje/CRC.

**Nigdy nie wysyłać:** `3A` (SN), `42` (unbind/reset), `43`, `91–93` (OTA).

## Meter1 – CMD 0x20 (indeksy w całej ramce)
- [5] światła: b0 nastrojowe, b1 prawy kier., b2 lewy kier., b3 tył, b4 przód
- [6] status1: b0 niski poziom baterii, b1 wymuszone odpięcie, b3 hamulec, b4 PIN ustawiony, b5 zablokowana, b6 tempomat aktywny, b7 ładowanie
- [7] kod błędu, [8] bateria %
- prędkość = ([9] | ([13]&3)<<8) × 0.1 km/h
- średnia = ([10] | (([13]>>2)&3)<<8) × 0.1
- zasięg = [11] | (([13]>>4)&3)<<8 km
- [12] aktualny limit km/h
- [15]: b0-2 tryb jazdy, b3 mph, b4 dwa silniki, b5-6 KERS
- [16]: b0-3 tryb świateł, b3/b4 temp. silnika, b5 smart lock, b6 tempomat włączony, b7 zero start
- [17..19] wersje protokołu / licznika / sterownika

## Meter2 – CMD 0x21 (int16 LE ze znakiem)
- [5,6] moc ×0.1, [7,8] napięcie ×0.01 V, [9,10] prąd ×0.01 A
- [11,12] trasa ×0.1 km, [13,14] przebieg km, [15] zasięg km, [16] śr. prąd ×0.1 A
- [17] jasność licznika, [18] temp. baterii °C, [19] temp. silnika °C, [20] dostępne tryby (bity), [21] temp. silnika 2

## BMS
- 0x64: [5] liczba ogniw, [6] nr pierwszego ogniwa (1, 9, 17, 25), od [7] napięcia uint16 BE w mV (do 8)
- 0x65: [5,6] napięcie mV BE, [7,8] prąd mA BE ze znakiem, [9,10] cykle, [11,12] pojemność pełna, [13,14] pozostała, [15] liczba czujników temp., [16..21] temperatury, [22,23] flagi stanu
