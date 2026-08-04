<h1 align="center">
  <img alt="Icon" width="75" src="img/icons/web/icon-192.png?raw=true">
  <br>
  RootlessViPER4Android
  <br>
</h1>
<h4 align="center">ViPER4Android-style audio effects on Android — <b>no root required</b>. A feature-extended fork of RootlessJamesDSP.</h4>

<p align="center">
  <a href="https://github.com/alienware377/RootlessJamesDSP-ViPER4Android-Edition/actions/workflows/build-fork.yml">
      <img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/alienware377/RootlessJamesDSP-ViPER4Android-Edition/build-fork.yml?branch=viper-extras">
  </a>
  <a href="https://github.com/alienware377/RootlessJamesDSP-ViPER4Android-Edition/releases">
      <img alt="Release" src="https://img.shields.io/github/v/release/alienware377/RootlessJamesDSP-ViPER4Android-Edition?include_prereleases">
  </a>
  <a href="LICENSE">
      <img alt="License" src="https://img.shields.io/github/license/alienware377/RootlessJamesDSP-ViPER4Android-Edition">
  </a>
</p>

<p align="center">
  <a href="#-features">Features</a> •
  <a href="#-download--install">Download</a> •
  <a href="#-limitations">Limitations</a> •
  <a href="#-faq">FAQ</a> •
  <a href="#-credits--thanks">Credits</a>
</p>

---

**RootlessViPER4Android** (RootlessJamesDSP — ViPER4Android Edition) is a system-wide **Android equalizer and audio effects app** that brings the beloved **ViPER4Android (V4A)** sound experience to **non-rooted phones**. It combines the powerful open-source **JamesDSP** engine with native re-implementations of ViPER's classic effects — Dynamic System, ViPER Bass, ViPER Clarity, Spectrum Extension, Field Surround, Tube Simulator (6N1J) and many more — all running **without root, without Magisk, and without custom ROMs**.

## ✨ Features

### 🐍 ViPER4Android effect suite (native ports)
Arranged in the classic V4A order:

| Effect | What it does |
|---|---|
| **Playback gain control (AGC)** | Evens out quiet/loud tracks automatically |
| **FET compressor** | Punchy studio-style compression |
| **ViPER-DDC** | Headphone correction profiles — **446 official DDC files bundled!** |
| **Spectrum extension** | Restores sparkle & air to compressed music |
| **FIR equalizer** | Precision fixed-band EQ |
| **Convolver** | Impulse response processing |
| **Field surround** | Widens the stereo field naturally |
| **Differential surround** | Classic V4A channel-delay surround |
| **Headphone surround +** | Speakers-in-a-room simulation for headphones |
| **Reverberation** | Full room reverb (size, damping, width, wet/dry) |
| **Dynamic system** | ViPER Dynamic Bass with all **19 original device presets** + custom mode |
| **Tube simulator (6N1J)** | Warm analog tube saturation (up to 24 dB drive) |
| **ViPER bass** | Natural / Pure Bass+ / Subwoofer modes |
| **ViPER clarity** | Natural / OZone+ / XHiFi treble enhancement |
| **Auditory system protection (Cure+)** | Anti-fatigue crossfeed for long sessions |
| **Speaker optimization** | Corrective tuning for phone speakers |

### 🎚 Plus the full JamesDSP toolkit
Output limiter (with selectable peak / soft-saturation modes), auto-loudness compander, bass boost, **dual-band psychoacoustic bass exciter**, stereo widener, crossfeed, virtual room reverb, arbitrary-response graphic EQ (AutoEQ import), and a scriptable **Liveprog (EEL2)** engine.

### 💜 Quality-of-life
- Plain-language "ℹ️ what is this?" explainers on every section
- Smart UI: irrelevant sliders hide automatically
- Crash-safe DSP dispatch with built-in diagnostics
- Works per-app, session-based — no audio HAL patching

## 📲 Download & Install
1. Grab the latest APK from the [**Releases**](https://github.com/alienware377/RootlessJamesDSP-ViPER4Android-Edition/releases) page (or fresh builds from [Actions](https://github.com/alienware377/RootlessJamesDSP-ViPER4Android-Edition/actions)).
2. Install and follow the in-app onboarding — it walks you through the permission setup step-by-step.
3. Play music, open the app, and start flipping switches 🎶

Settings from the original RootlessJamesDSP carry over; this fork installs as the same package and updates in place.

## ⚠️ Limitations
Rootless audio capture on Android has inherent restrictions (inherited from upstream RootlessJamesDSP):
* Apps that block internal audio capture (some streaming apps, e.g. Spotify) are unaffected unless patched
* Calls and protected media may bypass processing
* A persistent capture notification is required by Android

See the [upstream documentation](https://github.com/timschneeberger/RootlessJamesDSP) for details and workarounds.

## ❓ FAQ
**Is this really ViPER4Android?**
The original V4A is closed-source and requires root. This project re-implements its most-loved effects natively on top of the JamesDSP engine — same spirit, same workflow, zero root.

**Do I need Magisk / a custom kernel / a driver install?**
Nope. Nothing to flash, no "driver" status screen, no SELinux tricks.

**Will it work on Android 13/14/15?**
It targets modern Android and is tested on current versions. If something breaks, open an issue with the in-app crash report.

## 🙏 Credits & Thanks
This fork stands entirely on the shoulders of giants — thank you!

* **[James Fung (@james34602)](https://github.com/james34602)** — creator of **[JamesDSP](https://github.com/james34602/JamesDSPManager)**, the brilliant open-source audio engine powering every effect here
* **[Tim Schneeberger (@timschneeberger)](https://github.com/timschneeberger)** — creator of **[RootlessJamesDSP](https://github.com/timschneeberger/RootlessJamesDSP)**, which made system-wide DSP without root a reality and is the direct base of this fork
* **The ViPER's Audio team (ViPER520 & zhuhang)** — creators of the legendary **[ViPER4Android](https://github.com/vipersaudio)**, whose effect designs inspired every port in this edition
* All upstream contributors and translators of both projects 💜

## 📄 License
This project is licensed under **GPL-3.0**, same as upstream RootlessJamesDSP. See [LICENSE](LICENSE). ViPER4Android is a trademark of its respective owners; this project is an independent, unaffiliated re-implementation.

---
<sub>Keywords: ViPER4Android no root, V4A without root, rootless equalizer Android, system-wide equalizer, JamesDSP fork, Android audio effects, bass boost, DDC headphone correction, AutoEQ, spectrum extension, tube amp simulator</sub>
