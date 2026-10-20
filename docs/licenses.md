# Third-party material

## Inter (the app's font)

`android/core-ui/src/main/res/font/inter_regular.ttf`: Inter 3.019, Regular, with Latin and Cyrillic.
Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter).
Licensed under the SIL Open Font License, Version 1.1: https://openfontlicense.org (the license text is also in the font file
itself, in its name table). The file is used unchanged, as in FatCodex.

## The avocado palette

The "avocado" theme adapts the colours of two published themes of that name. Only colour values are taken, no code or
files; where a colour does not reach WCAG 4.5:1 on its background in this app, the same hue is darkened.

- Light: [Avocado colour scheme for KDE Plasma 5](https://github.com/Sandrone69/avocado-kde-color-scheme) by Sandrone69
  (GPL-3.0): `#4B4035` text, `#704012` time, `#4A7337` actions, `#FCFCFC` on them, `#DDD48F` (the flesh) for lines; the page
  is a lighter cream of the same flesh, `#F3F1D6`.
- Dark: [Avocado theme for Omarchy/Hyprland](https://github.com/evanklem/omarchy-avocado-theme) by evanklem (GPL-3.0):
  `#000000` page, `#F2FFBE` text, `#99A91D` secondary text, `#B5E631` actions, `#EAA621` time, `#3EAE0A` success,
  `#E6654F` errors, `#160000` pressed.

## From FatCodex

The lock (`ui/lock`), the GitHub update check and installer (`ui/update`), the Keystore cipher for the model key and parts of
the design system come from FatCodex by the same author (MIT), adapted to SpendCodex.

## Libraries

AndroidX (Compose, Room, Lifecycle, Biometric), Hilt, kotlinx.serialization, kotlinx.coroutines, OkHttp: Apache License 2.0.
