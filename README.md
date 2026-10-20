# OpenPFD

Aplikacja na Androida wyświetlająca realistyczny **Primary Flight Display** (Airbus A320, Boeing 737, Garmin G1000 SVT) oraz analogowy panel **Cessny 172** na pełnym ekranie zasilany **prawdziwymi danymi** z czujników telefonu i GPS.

| Parametr | Źródło |
|---|---|
| Pochylenie / przechylenie | wektor rotacji (żyroskop + akcelerometr + magnetometr) |
| Kurs magnetyczny | wektor rotacji |
| Kulka ślizgu (beta) | akcelerometr – przyspieszenie poprzeczne |
| Prędkość + trend 10 s | GPS (prędkość względem ziemi) |
| Kąt drogi, wektor toru lotu (FPV) | GPS + deklinacja magnetyczna (model WMM) |
| Wysokość, V/S | barometr (bez barometru: GPS MSL) |
| **QNH** | najbliższy **METAR** (aviationweather.gov) → Open-Meteo `pressure_msl` → barometr + wysokość GPS |

## Obsługa
- PFD jest zawsze **kwadratowy, na środku ekranu**, działa w pionie i poziomie.
- Telefon trzymaj **pionowo, ekranem do siebie** (jak przyrząd na desce).
- **Długie przytrzymanie** – kalibracja poziomu.
- **Dotknięcie okna ciśnienia** – przełączenie QNH / STD.
- Przycisk **≡** – menu: motyw, kalibracja, hPa/inHg, FPV, aktualizacje.

## Aktualizacje
Każdy merge do `main` buduje podpisany APK i publikuje **GitHub Release** `v1.0.N`.
Aplikacja co 6 h (i przy starcie) sprawdza najnowszy release i proponuje instalację.

> Nie do nawigacji lotniczej.
