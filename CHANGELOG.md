# Changelog

## 1.3.0

- Zgłaszanie błędów i sugestii: nowa pozycja „Zgłoś błąd lub sugestię” – opis trafia prosto do autora przez redzikowski.com, e-mail jest opcjonalny.
- Po nieoczekiwanym zamknięciu aplikacja przy następnym uruchomieniu proponuje wysłanie raportu o błędzie – tylko za zgodą użytkownika.

## 1.2.0

- Angielska wersja językowa (domyślna); polska wybierana automatycznie przy polskim języku systemu. Wszystkie teksty interfejsu przeniesione do zasobów `strings.xml`.
- Flagi jakości danych przy krawędzi przyrządu: NO GPS, GPS ALT (brak barometru), NO QNH, wiek QNH powyżej 90 min, NO NET, HDG REL, NO TERR. Ten sam stan w ustawieniach (wiersz „Dane”).
- Flagi awarii ATT / SPD / ALT / HDG / V/S rysowane w ramce, jak w prawdziwych PFD.
- Wspólne komponenty rysowania (flagi, szewrony pochylenia, wektor toru lotu) w `Gfx.kt` zamiast kopii w każdym rendererze.

## 1.1.1

- Bez żadnych wzmianek o GitHubie w aplikacji: nowy adres serwera aktualizacji, komunikaty błędów bez nazw zewnętrznych serwerów.

## 1.1.0

- Naprawione sprawdzanie aktualizacji: nowsze wydania są teraz poprawnie wykrywane.
- Numer versionCode liczony z wersji: major·10000 + minor·100 + patch.

## 1.0.24

- Dopasowane kolorystycznie tła ikon aplikacji i spójne zasoby graficzne.

