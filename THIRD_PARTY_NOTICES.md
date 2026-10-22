# Third-Party Notices

## lls-manager 与 plusls

plusls 是 lls-manager 的作者，也是 DDS Manager 的重大、奠基贡献者。DDS 的产品方向、跨服管理思路和最初需求来自 lls-manager；没有 plusls 编写的 lls-manager，就没有 DDS Manager。对此致以明确而长期的感谢。

- Author: https://github.com/plusls
- Project: https://github.com/plusls/lls-manager

lls-manager 不是 DDS Manager 的运行依赖，也未被打包。上游仓库未附带明确软件许可证，因此 DDS 保留功能与产品层面的传承，并以独立代码完成重构。技术核查记录由维护者保存于内部开发审计资料；“重大贡献者”是对奠基工作的署名和感谢，不表示 plusls 对 DDS 当前代码或发行版本负责。

## Localization contribution — JonathanJourdan

[JonathanJourdan](https://github.com/JonathanJourdan) contributed the initial multilingual implementation in public [DDS-Manager PR #1](https://github.com/x-oOvOo-x/DDS-Manager/pull/1), source commit `d231d3f13666e27bfb872dae78b8b117386ce9ca`. DDS Manager 0.7.0 integrates, adapts and extends that contribution with additional languages and compatibility work. The initial author must remain credited even when a public release is published as a clean source snapshot without individual development commit ancestry. This credit does not imply endorsement of subsequent changes.

Keep this attribution and the existing plusls/lls-manager acknowledgment, `LICENSE`, and all other required notices when distributing source or binaries.

## Google Gson (bundled/relocated runtime dependency)

- Package: Gson `2.13.2`, `com.google.code.gson:gson`
- Upstream: https://github.com/google/gson
- License: Apache License, Version 2.0
- Copyright: Google Inc. and contributors (see license text)
- Bundling: Gson is included and relocated in the DDS Manager Shadow JAR under `ddsmanager.lib.gson`. DDS Manager does not claim authorship of Gson.
- License copy: [`LICENSES/GSON-APACHE-2.0.txt`](LICENSES/GSON-APACHE-2.0.txt); include this license with redistributed sources and binaries.

The Gradle Wrapper JAR present in the source snapshot is build tooling from the Gradle project (Apache License 2.0; https://github.com/gradle/gradle), not a DDS Manager runtime dependency. The Apache-2.0 terms are also included in the same LICENSES folder.
