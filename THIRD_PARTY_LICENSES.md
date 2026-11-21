# Third-party notices

Bundled license texts are in [app/src/main/assets/licenses](app/src/main/assets/licenses) and are included in the APK.

| Component | Version / source | License |
| --- | --- | --- |
| Android Gradle Plugin | 8.11.1 | [Apache-2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| Gradle and Wrapper | 8.13 | [Apache-2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| AndroidX, Jetpack Compose, Material icons | Compose BOM 2025.05.01, Activity 1.10.1, Lifecycle 2.9.1 | [Apache-2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| Kotlin, Coroutines, Serialization | 2.1.21, 1.10.2, 1.8.1 | [Apache-2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| ONNX Runtime Android | 1.23.2 | [MIT](app/src/main/assets/licenses/ONNX-Runtime.txt) and [third-party notices](app/src/main/assets/licenses/ONNX-Runtime-ThirdPartyNotices.txt) |
| Open-Unmix checkpoint and network architecture | [UMX](https://doi.org/10.5281/zenodo.3370486), [UMXHQ](https://doi.org/10.5281/zenodo.3370489) | [MIT](app/src/main/assets/licenses/Open-Unmix.txt) |
| JUnit 4 (tests) | 4.13.2 | [EPL-1.0](https://junit.org/junit4/license.html) |

The FFT, YIN-based detector, resampler and PSOLA renderer are implemented in Pitchline. Android platform media libraries are used through public SDK APIs. Small ONNX graphs shipped with the app use the official unmodified Open-Unmix checkpoint files as external tensor data. The app downloads the identical weights from the [Pitchline GitHub mirror](https://github.com/tstyr/pitchline-android/releases/tag/models-v1), with Zenodo as a fallback, and verifies the original SHA-256 before use. The mirror includes the MIT copyright and permission notices. PyTorch and python-onnx tooling are used for graph generation and empirical verification on the development machine.
